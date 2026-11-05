package com.example.payment.refund;

import com.example.payment.audit.AuditLog;
import com.example.payment.common.DomainException;
import com.example.payment.common.Hashing;
import com.example.payment.common.IdempotencyStore;
import com.example.payment.common.OutboxWriter;
import com.example.payment.ledger.LedgerService;
import com.example.payment.merchant.ApiClient;
import com.example.payment.payment.PaymentService;
import com.example.payment.provider.PaymentMethod;
import com.example.payment.provider.PaymentProvider;
import com.example.payment.provider.ProviderRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What: Creates partial and full refunds of a succeeded payment (UC-08).
 *
 * <p>Why: A refund must never exceed what was paid, even when requests arrive together.
 *
 * <p>How: Locks the payment, reserves the amount by saving a {@code PENDING} refund, and commits.
 * The provider is called outside any transaction, then the result is saved. A failed refund stops
 * counting, which releases its reservation. A timeout stays {@code PENDING} until a status check.
 * The ledger is posted only when a refund succeeds.
 *
 * <p>How to use: Call from the REST controller. Send an {@code Idempotency-Key}.
 *
 * <p>Safety: The payment's own status never changes. Its refunded amount comes from its refunds.
 *
 * <p>Tests: {@code RefundApiIntegrationTest}.
 */
@Service
public class RefundService {

  private static final Logger LOG = LoggerFactory.getLogger(RefundService.class);
  private static final Duration PENDING_GRACE = Duration.ofMinutes(1);
  private static final int BATCH = 100;

  private final PaymentService payments;
  private final IdempotencyStore idempotency;
  private final LedgerService ledger;
  private final ProviderRegistry providers;
  private final OutboxWriter outbox;
  private final AuditLog audit;
  private final JdbcClient jdbc;
  private final TransactionTemplate transaction;
  private final Clock clock;

  RefundService(
      PaymentService payments,
      IdempotencyStore idempotency,
      LedgerService ledger,
      ProviderRegistry providers,
      OutboxWriter outbox,
      AuditLog audit,
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.payments = payments;
    this.idempotency = idempotency;
    this.ledger = ledger;
    this.providers = providers;
    this.outbox = outbox;
    this.audit = audit;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** A refund as returned by the API. {@code sandbox} is always true. */
  public record Refund(
      UUID refundId,
      UUID paymentId,
      UUID merchantId,
      PaymentMethod method,
      long amount,
      String currency,
      String reason,
      String status,
      String actor,
      boolean sandbox,
      Instant createdAt,
      Instant updatedAt) {}

  /** The result of a create call. */
  public record Created(Refund refund, boolean replayed) {}

  /** A payment's refunds, with what can still be refunded. */
  public record RefundList(long refundableAmount, List<Refund> items) {}

  public Created create(ApiClient client, UUID paymentId, String key, CreateRefundRequest request) {
    IdempotencyStore.Claim claim =
        transaction.execute(status -> reserve(client, paymentId, key, request));
    Refund refund = find(claim.resourceId());
    if (claim.replayed()) {
      return new Created(refund, true);
    }
    resolve(refund, false);
    return new Created(find(refund.refundId()), false);
  }

  private IdempotencyStore.Claim reserve(
      ApiClient client, UUID paymentId, String key, CreateRefundRequest request) {
    PaymentService.RefundablePayment payment = payments.lockForRefund(paymentId, client);
    IdempotencyStore.Claim claim =
        idempotency.claim(
            payment.merchantId(),
            key,
            Hashing.sha256Hex("refund.create|" + paymentId + "|" + request),
            UUID.randomUUID());
    if (claim.replayed()) {
      return claim;
    }
    if (!payment.paid()) {
      throw new DomainException(
          HttpStatus.CONFLICT,
          "PAYMENT_NOT_REFUNDABLE",
          "Only a succeeded payment can be refunded.");
    }
    if (request.amount() > payment.amount() - reserved(paymentId)) {
      throw new DomainException(
          HttpStatus.CONFLICT,
          "REFUND_EXCEEDS_BALANCE",
          "The amount is more than the refundable balance.");
    }
    String actor = client.actor();
    Timestamp now = Timestamp.from(clock.instant());
    jdbc.sql(
            """
            INSERT INTO refunds
              (id, payment_id, merchant_id, method, amount, currency, reason, state, actor,
               created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
            """)
        .params(
            claim.resourceId(),
            paymentId,
            payment.merchantId(),
            payment.method().name(),
            request.amount(),
            payment.currency(),
            request.reason(),
            actor,
            now,
            now)
        .update();
    audit.record(actor, "refund.created", "refund", claim.resourceId().toString());
    return claim;
  }

  /** Lists a payment's refunds for a caller allowed to see the payment. */
  public RefundList list(ApiClient client, UUID paymentId) {
    PaymentService.RefundablePayment payment =
        transaction.execute(status -> payments.lockForRefund(paymentId, client));
    long refundable = payment.paid() ? payment.amount() - reserved(paymentId) : 0;
    List<Refund> items =
        jdbc.sql("SELECT * FROM refunds WHERE payment_id = ? ORDER BY created_at DESC LIMIT 100")
            .param(paymentId)
            .query(RefundService::map)
            .list();
    return new RefundList(refundable, items);
  }

  /** Completed refund totals. {@code amount} is whole rupiah. */
  public record RefundStats(long count, long amount) {}

  /** Staff see every merchant unless they name one. A merchant only sees its own. */
  public RefundStats stats(ApiClient client, UUID merchantId) {
    UUID scope = client.role() == ApiClient.Role.MERCHANT ? client.merchantId() : merchantId;
    return jdbc.sql(
            """
            SELECT count(*) AS count, coalesce(sum(amount), 0) AS amount FROM refunds
            WHERE state = 'SUCCEEDED' AND (?::uuid IS NULL OR merchant_id = ?)
            """)
        .params(scope, scope)
        .query((rs, row) -> new RefundStats(rs.getLong("count"), rs.getLong("amount")))
        .single();
  }

  /** Asks the provider about refunds whose outcome is still unknown. */
  @Scheduled(cron = "${payment.sweeper.cron}")
  public void resolvePending() {
    Timestamp cutoff = Timestamp.from(clock.instant().minus(PENDING_GRACE));
    jdbc.sql(
            """
            SELECT * FROM refunds WHERE state = 'PENDING' AND created_at < ?
            ORDER BY created_at LIMIT ?
            """)
        .params(cutoff, BATCH)
        .query(RefundService::map)
        .list()
        .forEach(refund -> resolve(refund, true));
  }

  private void resolve(Refund refund, boolean check) {
    PaymentProvider provider = providers.forMethod(refund.method());
    PaymentProvider.RefundRequest request =
        new PaymentProvider.RefundRequest(refund.refundId(), refund.paymentId(), refund.amount());
    PaymentProvider.RefundOutcome outcome;
    try {
      outcome = check ? provider.checkRefund(request) : provider.refund(request);
    } catch (RuntimeException e) {
      LOG.warn("Refund {} has an unknown outcome and stays pending.", refund.refundId(), e);
      return;
    }
    transaction.executeWithoutResult(status -> finish(refund, outcome));
  }

  private void finish(Refund refund, PaymentProvider.RefundOutcome outcome) {
    int changed =
        jdbc.sql("UPDATE refunds SET state = ?, updated_at = ? WHERE id = ? AND state = 'PENDING'")
            .params(outcome.name(), Timestamp.from(clock.instant()), refund.refundId())
            .update();
    if (changed == 0) {
      return;
    }
    boolean succeeded = outcome == PaymentProvider.RefundOutcome.SUCCEEDED;
    if (succeeded) {
      ledger.postRefund(
          refund.merchantId(), refund.refundId(), refund.paymentId(), refund.amount());
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("refundId", refund.refundId());
    payload.put("paymentId", refund.paymentId());
    payload.put("status", outcome);
    payload.put("amount", refund.amount());
    payload.put("currency", refund.currency());
    outbox.write(succeeded ? "refund.succeeded" : "refund.failed", refund.merchantId(), payload);
  }

  /** The amount held by refunds that have not failed. */
  private long reserved(UUID paymentId) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(amount), 0) FROM refunds
            WHERE payment_id = ? AND state IN ('PENDING', 'SUCCEEDED')
            """)
        .param(paymentId)
        .query(Long.class)
        .single();
  }

  private Refund find(UUID refundId) {
    return jdbc.sql("SELECT * FROM refunds WHERE id = ?")
        .param(refundId)
        .query(RefundService::map)
        .single();
  }

  private static Refund map(ResultSet rs, int row) throws SQLException {
    return new Refund(
        rs.getObject("id", UUID.class),
        rs.getObject("payment_id", UUID.class),
        rs.getObject("merchant_id", UUID.class),
        PaymentMethod.valueOf(rs.getString("method")),
        rs.getLong("amount"),
        rs.getString("currency"),
        rs.getString("reason"),
        rs.getString("state"),
        rs.getString("actor"),
        true,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}
