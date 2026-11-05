package com.example.payment.checkout;

import com.example.payment.common.DomainException;
import com.example.payment.common.Hashing;
import com.example.payment.common.IdempotencyStore;
import com.example.payment.merchant.ApiClient;
import com.example.payment.provider.PaymentMethod;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Creates checkout sessions and returns them to authorized callers.
 *
 * <p>Why: It keeps idempotency, limits, and merchant scoping out of the controller.
 *
 * <p>How: Claims the idempotency key first. A replay returns the existing checkout before any
 * time-based rule runs, so a retry of a valid request still succeeds later. Any rejection rolls
 * back the transaction, which also removes the key.
 *
 * <p>How to use: Call from the REST controller with the authenticated merchant's ID.
 *
 * <p>Safety: A checkout the caller may not see is reported as not found, which hides whether it
 * exists.
 *
 * <p>Tests: {@code CheckoutApiIntegrationTest}.
 */
@Service
public class CheckoutService {

  static final String CURRENCY = "IDR";
  private static final Set<String> METADATA_KEYS = Set.of("campaign", "channel", "note");
  private static final int METADATA_VALUE_MAX = 128;
  private static final List<PaymentMethod> CHANNELS = List.of(PaymentMethod.values());

  private final CheckoutSessionRepository sessions;
  private final EntityManager entityManager;
  private final IdempotencyStore idempotency;
  private final CheckoutTokenService tokens;
  private final CheckoutProperties properties;
  private final LatestPaymentLookup latestPayments;
  private final JdbcClient jdbc;
  private final Clock clock;

  public CheckoutService(
      CheckoutSessionRepository sessions,
      EntityManager entityManager,
      IdempotencyStore idempotency,
      CheckoutTokenService tokens,
      CheckoutProperties properties,
      LatestPaymentLookup latestPayments,
      JdbcClient jdbc,
      Clock clock) {
    this.sessions = sessions;
    this.entityManager = entityManager;
    this.idempotency = idempotency;
    this.tokens = tokens;
    this.properties = properties;
    this.latestPayments = latestPayments;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** What the payment module may know about a checkout. */
  public record PayableCheckout(
      UUID id, UUID merchantId, long amount, String currency, Instant expiresAt, boolean payable) {}

  /** Returns a checkout for a merchant that owns it, or for staff. */
  @Transactional(readOnly = true)
  public PayableCheckout findForClient(UUID checkoutId, ApiClient client) {
    CheckoutSession session = find(checkoutId);
    if (client.role() == ApiClient.Role.MERCHANT
        && !session.merchantId().equals(client.merchantId())) {
      throw notFound();
    }
    return payable(session);
  }

  /** Returns a checkout for a customer holding its token. */
  @Transactional(readOnly = true)
  public PayableCheckout findForCustomer(UUID checkoutId, String token) {
    if (!tokens.isValid(checkoutId, token)) {
      throw notFound();
    }
    return payable(find(checkoutId));
  }

  /**
   * Returns a checkout without an access check. Callers must already have authorized the caller.
   */
  @Transactional(readOnly = true)
  public PayableCheckout findById(UUID checkoutId) {
    return payable(find(checkoutId));
  }

  public boolean isValidToken(UUID checkoutId, String token) {
    return tokens.isValid(checkoutId, token);
  }

  /**
   * Settles a checkout with the first successful attempt.
   *
   * @return false when another attempt already settled it
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markPaid(UUID checkoutId, UUID attemptId) {
    return jdbc.sql(
                """
                UPDATE checkout_sessions SET paid_attempt_id = ?
                WHERE id = ? AND paid_attempt_id IS NULL
                """)
            .params(attemptId, checkoutId)
            .update()
        == 1;
  }

  private PayableCheckout payable(CheckoutSession session) {
    return new PayableCheckout(
        session.id(),
        session.merchantId(),
        session.amount(),
        session.currency(),
        session.expiresAt(),
        session.status(clock.instant()) == CheckoutSession.Status.OPEN);
  }

  private LatestPaymentLookup.Summary latestPayment(UUID checkoutId) {
    return latestPayments.latest(checkoutId).orElse(null);
  }

  /** The result of a create call. */
  public record Created(CheckoutResponse checkout, boolean replayed) {}

  @Transactional
  public Created create(UUID merchantId, String idempotencyKey, CreateCheckoutRequest request) {
    IdempotencyStore.Claim claim =
        idempotency.claim(
            merchantId,
            idempotencyKey,
            Hashing.sha256Hex(request.fingerprint()),
            UUID.randomUUID());
    if (claim.replayed()) {
      return new Created(merchantView(sessions.findById(claim.resourceId()).orElseThrow()), true);
    }

    Instant now = clock.instant();
    validate(request, now);
    Instant expiresAt =
        request.expiresAt() != null ? request.expiresAt() : now.plus(properties.defaultTtl());
    Map<String, String> metadata = request.metadata() == null ? Map.of() : request.metadata();
    CheckoutSession session =
        new CheckoutSession(
            claim.resourceId(),
            merchantId,
            request.merchantOrderId(),
            request.amount(),
            request.currency(),
            request.description(),
            request.customerReference(),
            metadata,
            expiresAt,
            now);
    entityManager.persist(session);
    return new Created(merchantView(session), false);
  }

  /** Returns a checkout to a merchant that owns it, or to staff. */
  @Transactional(readOnly = true)
  public CheckoutResponse getForClient(UUID checkoutId, ApiClient client) {
    CheckoutSession session = find(checkoutId);
    if (client.role() == ApiClient.Role.MERCHANT
        && !session.merchantId().equals(client.merchantId())) {
      throw notFound();
    }
    return merchantView(session);
  }

  /** Returns the limited customer view of a checkout, given its token. */
  @Transactional(readOnly = true)
  public CheckoutResponse getForCustomer(UUID checkoutId, String token) {
    if (!tokens.isValid(checkoutId, token)) {
      throw notFound();
    }
    CheckoutSession session = find(checkoutId);
    return new CheckoutResponse(
        session.id(),
        session.merchantOrderId(),
        session.amount(),
        session.currency(),
        session.description(),
        session.status(clock.instant()),
        session.expiresAt(),
        CHANNELS,
        true,
        latestPayment(session.id()),
        null,
        null,
        null,
        null);
  }

  private CheckoutSession find(UUID checkoutId) {
    return sessions.findById(checkoutId).orElseThrow(CheckoutService::notFound);
  }

  private CheckoutResponse merchantView(CheckoutSession session) {
    return new CheckoutResponse(
        session.id(),
        session.merchantOrderId(),
        session.amount(),
        session.currency(),
        session.description(),
        session.status(clock.instant()),
        session.expiresAt(),
        CHANNELS,
        true,
        latestPayment(session.id()),
        properties.baseUrl() + "/checkout/" + session.id() + "#token=" + tokens.issue(session.id()),
        session.customerReference(),
        session.metadata(),
        session.createdAt());
  }

  private void validate(CreateCheckoutRequest request, Instant now) {
    if (!CURRENCY.equals(request.currency())) {
      throw DomainException.invalidField("currency", "Only IDR is supported.");
    }
    if (request.amount() > properties.maxAmount()) {
      throw DomainException.invalidField("amount", "Amount exceeds the simulated limit.");
    }
    if (request.expiresAt() != null) {
      if (!request.expiresAt().isAfter(now)) {
        throw DomainException.invalidField("expiresAt", "Expiry must be in the future.");
      }
      if (request.expiresAt().isAfter(now.plus(properties.maxTtl()))) {
        throw DomainException.invalidField("expiresAt", "Expiry is too far in the future.");
      }
    }
    if (request.metadata() != null) {
      request
          .metadata()
          .forEach((key, value) -> {
            if (!METADATA_KEYS.contains(key)) {
              throw DomainException.invalidField("metadata", "Metadata key is not allowed.");
            }
            if (value == null || value.length() > METADATA_VALUE_MAX) {
              throw DomainException.invalidField("metadata", "Metadata value is too long.");
            }
          });
    }
  }

  private static DomainException notFound() {
    return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Checkout not found.");
  }
}
