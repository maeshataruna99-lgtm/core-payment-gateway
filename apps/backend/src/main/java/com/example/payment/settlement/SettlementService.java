package com.example.payment.settlement;

import com.example.payment.audit.AuditLog;
import com.example.payment.common.DomainException;
import com.example.payment.common.Hashing;
import com.example.payment.common.IdempotencyStore;
import com.example.payment.common.OutboxWriter;
import com.example.payment.ledger.LedgerService;
import com.example.payment.ledger.LedgerService.Line;
import com.example.payment.ledger.LedgerService.LineKind;
import com.example.payment.merchant.ApiClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Calculates and finalizes simulated settlement batches (UC-09).
 *
 * <p>Why: What a merchant is owed must come from the ledger, and each movement must be paid once.
 *
 * <p>How: A preview saves nothing. Finalizing recalculates under a per-merchant lock and requires
 * the net amount the operator saw, so a batch can never differ from what was confirmed. A refund of
 * an already settled payment becomes a negative adjustment in the next batch.
 *
 * <p>How to use: Preview, show the result to an operator, then finalize with its {@code net}.
 *
 * <p>Safety: No bank transfer happens. A final batch and its lines cannot be changed, as the
 * database enforces. A batch with a net of zero or less is refused, and its lines wait for the next
 * batch.
 *
 * <p>Tests: {@code SettlementApiIntegrationTest}.
 */
@Service
public class SettlementService {

  private final LedgerService ledger;
  private final IdempotencyStore idempotency;
  private final OutboxWriter outbox;
  private final AuditLog audit;
  private final JdbcClient jdbc;
  private final Clock clock;

  SettlementService(
      LedgerService ledger,
      IdempotencyStore idempotency,
      OutboxWriter outbox,
      AuditLog audit,
      JdbcClient jdbc,
      Clock clock) {
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.audit = audit;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * A settlement's totals, in whole rupiah.
   *
   * @param adjustments zero or negative
   * @param id null for a preview
   * @param finalizedAt null for a preview
   */
  public record Settlement(
      UUID id,
      UUID merchantId,
      long gross,
      long fees,
      long refunds,
      long adjustments,
      long net,
      boolean sandbox,
      Instant finalizedAt,
      List<Line> lines) {}

  /** The result of finalizing. */
  public record Finalized(Settlement settlement, boolean replayed) {}

  @Transactional(readOnly = true)
  public Settlement preview(UUID merchantId) {
    requireMerchant(merchantId);
    return totals(null, merchantId, null, ledger.unsettled(merchantId));
  }

  @Transactional
  public Finalized finalizeBatch(ApiClient client, UUID merchantId, String key, long expectedNet) {
    requireMerchant(merchantId);
    // One batch per merchant at a time. The lock is released at commit.
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
        .param(merchantId.toString())
        .query(Object.class)
        .list();
    IdempotencyStore.Claim claim =
        idempotency.claim(
            merchantId,
            key,
            Hashing.sha256Hex("settlement.create|" + merchantId + "|" + expectedNet),
            UUID.randomUUID());
    if (claim.replayed()) {
      return new Finalized(get(client, claim.resourceId()), true);
    }
    List<Line> lines = ledger.unsettled(merchantId);
    Instant now = clock.instant();
    Settlement settlement = totals(claim.resourceId(), merchantId, now, lines);
    if (lines.isEmpty() || settlement.net() <= 0) {
      throw new DomainException(
          HttpStatus.CONFLICT,
          "NOTHING_TO_SETTLE",
          "This merchant has no positive amount to settle.");
    }
    if (settlement.net() != expectedNet) {
      throw new DomainException(
          HttpStatus.CONFLICT,
          "SETTLEMENT_CHANGED",
          "The amount changed since the preview. Preview again.");
    }
    jdbc.sql(
            """
            INSERT INTO settlement_batches
              (id, merchant_id, gross, fees, refunds, adjustments, net, actor, finalized_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            settlement.id(),
            merchantId,
            settlement.gross(),
            settlement.fees(),
            settlement.refunds(),
            settlement.adjustments(),
            settlement.net(),
            client.actor(),
            Timestamp.from(now))
        .update();
    ledger.postSettlement(merchantId, settlement.id(), lines, settlement.net());
    audit.record(client.actor(), "settlement.finalized", "settlement", settlement.id().toString());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("settlementId", settlement.id());
    payload.put("gross", settlement.gross());
    payload.put("fees", settlement.fees());
    payload.put("refunds", settlement.refunds());
    payload.put("adjustments", settlement.adjustments());
    payload.put("net", settlement.net());
    outbox.write("settlement.finalized", merchantId, payload);
    return new Finalized(settlement, false);
  }

  /** Returns a final batch to the merchant it belongs to, or to staff. */
  @Transactional(readOnly = true)
  public Settlement get(ApiClient client, UUID batchId) {
    Settlement batch =
        jdbc.sql("SELECT * FROM settlement_batches WHERE id = ?")
            .param(batchId)
            .query((rs, row) ->
                new Settlement(
                    rs.getObject("id", UUID.class),
                    rs.getObject("merchant_id", UUID.class),
                    rs.getLong("gross"),
                    rs.getLong("fees"),
                    rs.getLong("refunds"),
                    rs.getLong("adjustments"),
                    rs.getLong("net"),
                    true,
                    rs.getTimestamp("finalized_at").toInstant(),
                    ledger.settled(batchId)))
            .optional()
            .filter(s ->
                client.role() != ApiClient.Role.MERCHANT
                    || s.merchantId().equals(client.merchantId()))
            .orElseThrow(() ->
                new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Settlement not found."));
    return batch;
  }

  private void requireMerchant(UUID merchantId) {
    boolean exists =
        jdbc.sql("SELECT count(*) FROM merchants WHERE id = ?")
                .param(merchantId)
                .query(Long.class)
                .single()
            > 0;
    if (!exists) {
      throw new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Merchant not found.");
    }
  }

  private static Settlement totals(
      UUID id, UUID merchantId, Instant finalizedAt, List<Line> lines) {
    long gross = sum(lines, LineKind.PAYMENT);
    long fees = lines.stream().mapToLong(Line::fee).sum();
    long refunds = sum(lines, LineKind.REFUND);
    long adjustments = -sum(lines, LineKind.ADJUSTMENT);
    return new Settlement(
        id,
        merchantId,
        gross,
        fees,
        refunds,
        adjustments,
        gross - fees - refunds + adjustments,
        true,
        finalizedAt,
        lines);
  }

  private static long sum(List<Line> lines, LineKind kind) {
    return lines.stream().filter(l -> l.kind() == kind).mapToLong(Line::amount).sum();
  }
}
