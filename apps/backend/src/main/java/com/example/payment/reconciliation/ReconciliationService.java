package com.example.payment.reconciliation;

import com.example.payment.audit.AuditLog;
import com.example.payment.common.DomainException;
import com.example.payment.common.Hashing;
import com.example.payment.merchant.ApiClient;
import com.example.payment.payment.PaymentRecords;
import com.example.payment.payment.PaymentRecords.PaymentRecord;
import com.example.payment.reconciliation.ReconciliationMatcher.Finding;
import com.example.payment.reconciliation.SettlementFile.Row;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Runs a reconciliation and stores its report (UC-10).
 *
 * <p>Why: A difference between two records must be visible to a person, not fixed by a guess.
 *
 * <p>How: Validates the whole file, loads the payments to compare, runs {@link
 * ReconciliationMatcher}, and saves the run with its discrepancies.
 *
 * <p>How to use: Call from the REST controller. The same file can be run again, and each run is its
 * own record.
 *
 * <p>Safety: It only reads payments, and it writes nothing to payments or the ledger. Runs and
 * their discrepancies cannot be changed, as the database enforces.
 *
 * <p>Tests: {@code ReconciliationApiIntegrationTest}.
 */
@Service
class ReconciliationService {

  private final PaymentRecords payments;
  private final AuditLog audit;
  private final JdbcClient jdbc;
  private final Clock clock;

  ReconciliationService(PaymentRecords payments, AuditLog audit, JdbcClient jdbc, Clock clock) {
    this.payments = payments;
    this.audit = audit;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** A stored run. {@code discrepancies} is null in a list. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Run(
      UUID runId,
      UUID merchantId,
      Instant from,
      Instant to,
      String fileSha256,
      int rowCount,
      int matchedCount,
      int discrepancyCount,
      String actor,
      boolean sandbox,
      Instant createdAt,
      List<Discrepancy> discrepancies) {}

  /** One recorded difference. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Discrepancy(
      String type,
      String providerReference,
      UUID paymentId,
      String internalValue,
      String providerValue) {}

  @Transactional
  public Run run(ApiClient client, UUID merchantId, Instant from, Instant to, String file) {
    if (!from.isBefore(to)) {
      throw DomainException.invalidField("from", "The period must start before it ends.");
    }
    requireMerchant(merchantId);
    List<Row> rows = SettlementFile.parse(file);
    List<PaymentRecord> expected = payments.succeededBetween(merchantId, from, to);
    List<PaymentRecord> referenced =
        payments.withReferences(merchantId, rows.stream().map(Row::reference).distinct().toList());
    ReconciliationMatcher.Result result = ReconciliationMatcher.match(rows, expected, referenced);

    UUID runId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO reconciliation_runs
              (id, merchant_id, period_from, period_to, file_sha256, row_count, matched_count,
               actor, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            runId,
            merchantId,
            Timestamp.from(from),
            Timestamp.from(to),
            Hashing.sha256Hex(file),
            rows.size(),
            result.matched(),
            client.actor(),
            Timestamp.from(clock.instant()))
        .update();
    for (Finding finding : result.findings()) {
      jdbc.sql(
              """
              INSERT INTO reconciliation_discrepancies
                (run_id, type, provider_reference, payment_id, internal_value, provider_value)
              VALUES (?, ?, ?, ?, ?, ?)
              """)
          .params(
              runId,
              finding.type().name(),
              finding.reference(),
              finding.paymentId(),
              finding.internalValue(),
              finding.providerValue())
          .update();
    }
    audit.record(client.actor(), "reconciliation.run", "reconciliation_run", runId.toString());
    return get(runId);
  }

  @Transactional(readOnly = true)
  public Run get(UUID runId) {
    Run run =
        jdbc.sql(selectRuns("WHERE r.id = ?"))
            .param(runId)
            .query(ReconciliationService::mapRun)
            .optional()
            .orElseThrow(() ->
                new DomainException(
                    HttpStatus.NOT_FOUND, "NOT_FOUND", "Reconciliation run not found."));
    List<Discrepancy> discrepancies =
        jdbc.sql(
                """
                SELECT type, provider_reference, payment_id, internal_value, provider_value
                FROM reconciliation_discrepancies WHERE run_id = ? ORDER BY id
                """)
            .param(runId)
            .query((rs, row) ->
                new Discrepancy(
                    rs.getString("type"),
                    rs.getString("provider_reference"),
                    rs.getObject("payment_id", UUID.class),
                    rs.getString("internal_value"),
                    rs.getString("provider_value")))
            .list();
    return new Run(
        run.runId(),
        run.merchantId(),
        run.from(),
        run.to(),
        run.fileSha256(),
        run.rowCount(),
        run.matchedCount(),
        run.discrepancyCount(),
        run.actor(),
        run.sandbox(),
        run.createdAt(),
        discrepancies);
  }

  /** Lists a merchant's runs, newest first. */
  @Transactional(readOnly = true)
  public List<Run> list(UUID merchantId, int page, int size) {
    return jdbc.sql(
            selectRuns("WHERE r.merchant_id = ? ORDER BY r.created_at DESC LIMIT ? OFFSET ?"))
        .params(merchantId, size, (long) page * size)
        .query(ReconciliationService::mapRun)
        .list();
  }

  private static String selectRuns(String condition) {
    return """
    SELECT r.*, (SELECT count(*) FROM reconciliation_discrepancies d WHERE d.run_id = r.id)
      AS discrepancy_count
    FROM reconciliation_runs r
    """
        + condition;
  }

  private static Run mapRun(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Run(
        rs.getObject("id", UUID.class),
        rs.getObject("merchant_id", UUID.class),
        rs.getTimestamp("period_from").toInstant(),
        rs.getTimestamp("period_to").toInstant(),
        rs.getString("file_sha256"),
        rs.getInt("row_count"),
        rs.getInt("matched_count"),
        rs.getInt("discrepancy_count"),
        rs.getString("actor"),
        true,
        rs.getTimestamp("created_at").toInstant(),
        null);
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
}
