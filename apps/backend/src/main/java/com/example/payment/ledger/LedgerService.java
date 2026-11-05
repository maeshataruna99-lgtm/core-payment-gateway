package com.example.payment.ledger;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Posts money movements to the double-entry ledger and reports what is still unsettled.
 *
 * <p>Why: Balances must be derived from an append-only record, not kept in a field that can drift.
 *
 * <p>How: Each movement is one transaction with equal debits and credits. The second success on a
 * checkout goes to {@code unapplied_funds}, so it is refunded and never paid to the merchant.
 *
 * <p>How to use: Call inside the transaction of the change being recorded. All amounts are whole
 * rupiah, and the money is simulated.
 *
 * <p>Safety: The database rejects an unbalanced transaction, a repeated posting, and any change to
 * a posted row.
 *
 * <p>Tests: {@code LedgerIntegrationTest}.
 */
@Service
public class LedgerService {

  /** A ledger account. */
  private enum Account {
    provider_clearing,
    merchant_payable,
    fee_revenue,
    unapplied_funds
  }

  /** How an unsettled transaction counts in a settlement. */
  public enum LineKind {
    PAYMENT,
    REFUND,
    ADJUSTMENT
  }

  /**
   * One line of a settlement.
   *
   * @param referenceId the payment or refund behind this line
   */
  public record Line(UUID transactionId, LineKind kind, UUID referenceId, long amount, long fee) {}

  private static final long BASIS_POINTS = 10_000;

  private final JdbcClient jdbc;
  private final Clock clock;
  private final long feeBasisPoints;

  LedgerService(
      JdbcClient jdbc, Clock clock, @Value("${payment.fee.basis-points}") long feeBasisPoints) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.feeBasisPoints = feeBasisPoints;
  }

  /**
   * Records a successful payment.
   *
   * @param settlesCheckout false for a second success on a checkout, which earns no fee
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void postPayment(UUID merchantId, UUID paymentId, long amount, boolean settlesCheckout) {
    if (!settlesCheckout) {
      UUID id = transaction(merchantId, "UNAPPLIED_PAYMENT", paymentId, null);
      entry(id, Account.provider_clearing, "DEBIT", amount);
      entry(id, Account.unapplied_funds, "CREDIT", amount);
      return;
    }
    long fee = fee(amount);
    UUID id = transaction(merchantId, "PAYMENT", paymentId, null);
    entry(id, Account.provider_clearing, "DEBIT", amount);
    entry(id, Account.merchant_payable, "CREDIT", amount - fee);
    if (fee > 0) {
      entry(id, Account.fee_revenue, "CREDIT", fee);
    }
  }

  /** Records a completed refund against the account that holds the payment's money. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void postRefund(UUID merchantId, UUID refundId, UUID paymentId, long amount) {
    boolean unapplied =
        jdbc.sql(
                    """
                    SELECT count(*) FROM ledger_transactions
                    WHERE type = 'UNAPPLIED_PAYMENT' AND reference_id = ?
                    """)
                .param(paymentId)
                .query(Long.class)
                .single()
            > 0;
    UUID id =
        transaction(merchantId, unapplied ? "UNAPPLIED_REFUND" : "REFUND", refundId, paymentId);
    entry(id, unapplied ? Account.unapplied_funds : Account.merchant_payable, "DEBIT", amount);
    entry(id, Account.provider_clearing, "CREDIT", amount);
  }

  /** Lists what a merchant's next settlement would contain. */
  @Transactional(readOnly = true)
  public List<Line> unsettled(UUID merchantId) {
    return lines("t.merchant_id = ? AND m.transaction_id IS NULL", merchantId);
  }

  /** Lists what a final batch contains. */
  @Transactional(readOnly = true)
  public List<Line> settled(UUID batchId) {
    return lines("m.batch_id = ?", batchId);
  }

  /**
   * Records a batch's lines and payout.
   *
   * @throws RuntimeException if a line was already settled, which the primary key prevents
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void postSettlement(UUID merchantId, UUID batchId, List<Line> lines, long net) {
    for (Line line : lines) {
      jdbc.sql("INSERT INTO ledger_settlement_marks (transaction_id, batch_id) VALUES (?, ?)")
          .params(line.transactionId(), batchId)
          .update();
    }
    UUID id = transaction(merchantId, "SETTLEMENT", batchId, null);
    entry(id, Account.merchant_payable, "DEBIT", net);
    entry(id, Account.provider_clearing, "CREDIT", net);
  }

  private List<Line> lines(String condition, UUID parameter) {
    return jdbc.sql(
            """
            SELECT t.id, t.type, t.reference_id,
              (SELECT e.amount FROM ledger_entries e
               WHERE e.transaction_id = t.id AND e.account = 'provider_clearing') AS amount,
              coalesce((SELECT e.amount FROM ledger_entries e
               WHERE e.transaction_id = t.id AND e.account = 'fee_revenue'), 0) AS fee,
              -- A refund of a payment that was settled earlier corrects a final batch.
              EXISTS (SELECT 1 FROM ledger_transactions p
                      JOIN ledger_settlement_marks pm ON pm.transaction_id = p.id
                      WHERE p.type = 'PAYMENT' AND p.reference_id = t.related_id
                        AND (m.batch_id IS NULL OR pm.batch_id <> m.batch_id)) AS adjustment
            FROM ledger_transactions t
            LEFT JOIN ledger_settlement_marks m ON m.transaction_id = t.id
            WHERE t.type IN ('PAYMENT', 'REFUND') AND %s
            ORDER BY t.created_at, t.id
            """
                .formatted(condition))
        .param(parameter)
        .query((rs, row) -> {
          LineKind kind =
              "PAYMENT".equals(rs.getString("type"))
                  ? LineKind.PAYMENT
                  : rs.getBoolean("adjustment") ? LineKind.ADJUSTMENT : LineKind.REFUND;
          return new Line(
              rs.getObject("id", UUID.class),
              kind,
              rs.getObject("reference_id", UUID.class),
              rs.getLong("amount"),
              rs.getLong("fee"));
        })
        .list();
  }

  /** Integer math, rounding half up. A simulated rate, not a regulatory value. */
  private long fee(long amount) {
    return (amount * feeBasisPoints + BASIS_POINTS / 2) / BASIS_POINTS;
  }

  private UUID transaction(UUID merchantId, String type, UUID referenceId, UUID relatedId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO ledger_transactions
              (id, merchant_id, type, reference_id, related_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """)
        .params(id, merchantId, type, referenceId, relatedId, Timestamp.from(clock.instant()))
        .update();
    return id;
  }

  private void entry(UUID transactionId, Account account, String direction, long amount) {
    jdbc.sql(
            """
            INSERT INTO ledger_entries (transaction_id, account, direction, amount)
            VALUES (?, ?, ?, ?)
            """)
        .params(transactionId, account.name(), direction, amount)
        .update();
  }
}
