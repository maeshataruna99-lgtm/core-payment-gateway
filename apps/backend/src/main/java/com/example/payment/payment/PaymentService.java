package com.example.payment.payment;

import com.example.payment.checkout.CheckoutService;
import com.example.payment.checkout.CheckoutService.PayableCheckout;
import com.example.payment.common.DomainException;
import com.example.payment.common.Hashing;
import com.example.payment.common.IdempotencyStore;
import com.example.payment.merchant.ApiClient;
import com.example.payment.payment.PaymentStateMachine.Event;
import com.example.payment.provider.PaymentMethod;
import com.example.payment.provider.PaymentProvider;
import com.example.payment.provider.ProviderRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What: Starts payment attempts and returns them to authorized callers (UC-03 and UC-04).
 *
 * <p>Why: It keeps the order of work in one place: save the attempt, call the provider, then record
 * the result.
 *
 * <p>How: The attempt commits first, so a crash during the provider call leaves a record that the
 * sweeper can resolve. The provider call runs outside any transaction, and any failure from it is
 * treated as a timeout, because the outcome is unknown.
 *
 * <p>How to use: Call from the REST controller with either an API client or a customer token.
 *
 * <p>Safety: No API accepts a payment status. An attempt the caller may not see is reported as not
 * found. A repeated request never calls the provider again.
 *
 * <p>Tests: {@code PaymentApiIntegrationTest}, {@code ProviderCallTransactionTest}.
 */
@Service
public class PaymentService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);
  private static final int MAX_ATTEMPTS_SHOWN = 20;

  private final PaymentAttemptRepository attempts;
  private final PaymentEventService events;
  private final CheckoutService checkouts;
  private final IdempotencyStore idempotency;
  private final ProviderRegistry providers;
  private final EntityManager entityManager;
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;

  PaymentService(
      PaymentAttemptRepository attempts,
      PaymentEventService events,
      CheckoutService checkouts,
      IdempotencyStore idempotency,
      ProviderRegistry providers,
      EntityManager entityManager,
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.attempts = attempts;
    this.events = events;
    this.checkouts = checkouts;
    this.idempotency = idempotency;
    this.providers = providers;
    this.entityManager = entityManager;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** The result of a create call. */
  public record Created(PaymentResponse payment, boolean replayed) {}

  /** Starts a payment for an API client. Only the merchant that owns the checkout may do this. */
  public Created createForClient(ApiClient client, String key, CreatePaymentRequest request) {
    if (client.role() != ApiClient.Role.MERCHANT) {
      throw new DomainException(
          HttpStatus.FORBIDDEN, "FORBIDDEN", "Only a merchant or customer can start a payment.");
    }
    PayableCheckout checkout = checkouts.findForClient(request.checkoutId(), client);
    return create(checkout, key, request, "merchant:" + client.id());
  }

  /** Starts a payment for a customer holding the checkout's token. */
  public Created createForCustomer(String token, String key, CreatePaymentRequest request) {
    PayableCheckout checkout = checkouts.findForCustomer(request.checkoutId(), token);
    // Customers of one merchant choose keys independently, so their keys must not collide.
    return create(checkout, "customer:" + checkout.id() + ":" + key, request, "customer");
  }

  private Created create(
      PayableCheckout checkout, String key, CreatePaymentRequest request, String actor) {
    PaymentProvider provider = providers.forMethod(request.paymentMethod());
    IdempotencyStore.Claim claim =
        transaction.execute(status -> saveAttempt(checkout, key, request, provider, actor));
    if (claim.replayed()) {
      return new Created(view(attempts.findById(claim.resourceId()).orElseThrow(), true), true);
    }

    PaymentAttempt attempt = attempts.findById(claim.resourceId()).orElseThrow();
    try {
      events.applyProviderResult(
          attempt.id(), provider.initiate(attempt.toProviderRequest()), "provider");
    } catch (RuntimeException e) {
      LOG.warn("Provider call failed for attempt {}. Its outcome is unknown.", attempt.id(), e);
      events.apply(attempt.id(), Event.TIMEOUT, null, "provider");
    }
    return new Created(view(attempts.findById(attempt.id()).orElseThrow(), true), false);
  }

  private IdempotencyStore.Claim saveAttempt(
      PayableCheckout checkout,
      String key,
      CreatePaymentRequest request,
      PaymentProvider provider,
      String actor) {
    IdempotencyStore.Claim claim =
        idempotency.claim(
            checkout.merchantId(),
            key,
            Hashing.sha256Hex(request.fingerprint()),
            UUID.randomUUID());
    if (claim.replayed()) {
      return claim;
    }
    if (!checkouts.findById(checkout.id()).payable()) {
      throw new DomainException(
          HttpStatus.CONFLICT, "CHECKOUT_NOT_PAYABLE", "This checkout is expired or already paid.");
    }
    if (!provider.supportsChannel(request.channelCode())) {
      throw DomainException.invalidField(
          "channelCode", "This channel is not available for the payment method.");
    }
    PaymentAttempt attempt =
        new PaymentAttempt(
            claim.resourceId(),
            checkout.id(),
            checkout.merchantId(),
            request.paymentMethod(),
            request.channelCode(),
            checkout.amount(),
            checkout.currency(),
            checkout.expiresAt(),
            clock.instant());
    entityManager.persist(attempt);
    // The event row refers to the attempt, so the attempt must reach the database first.
    entityManager.flush();
    events.recordCreated(attempt, actor);
    return claim;
  }

  /** What the refund module may know about a payment. */
  public record RefundablePayment(
      UUID id, UUID merchantId, PaymentMethod method, long amount, String currency, boolean paid) {}

  /**
   * Locks a payment until the caller's transaction ends, so its refunds are checked one at a time.
   *
   * @throws DomainException not found, when the caller may not see the payment
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RefundablePayment lockForRefund(UUID paymentId, ApiClient client) {
    PaymentAttempt attempt =
        attempts.findByIdForUpdate(paymentId).orElseThrow(PaymentService::notFound);
    if (client.role() == ApiClient.Role.MERCHANT
        && !attempt.merchantId().equals(client.merchantId())) {
      throw notFound();
    }
    return new RefundablePayment(
        attempt.id(),
        attempt.merchantId(),
        attempt.method(),
        attempt.amount(),
        attempt.currency(),
        attempt.state() == PaymentStateMachine.State.SUCCEEDED);
  }

  /** Returns an attempt to the merchant that owns it, or to staff. */
  public PaymentResponse getForClient(UUID paymentId, ApiClient client) {
    PaymentAttempt attempt = attempts.findById(paymentId).orElseThrow(PaymentService::notFound);
    if (client.role() == ApiClient.Role.MERCHANT
        && !attempt.merchantId().equals(client.merchantId())) {
      throw notFound();
    }
    return view(attempt, true);
  }

  /** Returns an attempt to a customer holding its checkout's token, without internal details. */
  public PaymentResponse getForCustomer(UUID paymentId, String token) {
    PaymentAttempt attempt = attempts.findById(paymentId).orElseThrow(PaymentService::notFound);
    if (!checkouts.isValidToken(attempt.checkoutId(), token)) {
      throw notFound();
    }
    return view(attempt, false);
  }

  private PaymentResponse view(PaymentAttempt attempt, boolean internal) {
    List<PaymentResponse.AttemptSummary> siblings =
        attempts
            .findByCheckoutIdOrderByCreatedAtDesc(
                attempt.checkoutId(), Limit.of(MAX_ATTEMPTS_SHOWN))
            .stream()
            .map(a -> new PaymentResponse.AttemptSummary(a.id(), a.state(), a.method()))
            .toList();
    PaymentResponse.Instruction instruction =
        attempt.instructionValue() == null
            ? null
            : new PaymentResponse.Instruction(
                attempt.instructionLabel(), attempt.instructionValue());
    return new PaymentResponse(
        attempt.id(),
        attempt.checkoutId(),
        attempt.state(),
        attempt.method(),
        attempt.channelCode(),
        attempt.paidMethod(),
        attempt.amount(),
        attempt.currency(),
        true,
        internal ? attempt.providerReference() : null,
        instruction,
        attempt.createdAt(),
        attempt.updatedAt(),
        siblings,
        internal ? timeline(attempt.id()) : null,
        internal ? discrepancies(attempt.id()) : null);
  }

  private List<PaymentResponse.DiscrepancyEntry> discrepancies(UUID attemptId) {
    return jdbc.sql(
            """
            SELECT type, detail, created_at FROM payment_discrepancies
            WHERE attempt_id = ? ORDER BY created_at
            """)
        .param(attemptId)
        .query((rs, row) ->
            new PaymentResponse.DiscrepancyEntry(
                rs.getString("type"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  private List<PaymentResponse.TimelineEntry> timeline(UUID attemptId) {
    return jdbc.sql(
            """
            SELECT created_at, event, from_state, to_state FROM payment_attempt_events
            WHERE attempt_id = ? ORDER BY id
            """)
        .param(attemptId)
        .query((rs, row) ->
            new PaymentResponse.TimelineEntry(
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("event"),
                rs.getString("from_state"),
                rs.getString("to_state")))
        .list();
  }

  private static DomainException notFound() {
    return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Payment not found.");
  }
}
