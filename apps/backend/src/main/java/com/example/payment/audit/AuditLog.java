package com.example.payment.audit;

import com.example.payment.common.CorrelationIdFilter;
import com.example.payment.common.Hashing;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Records privileged actions in a hash chain.
 *
 * <p>Why: A reviewer must be able to see who did what, and detect an edit made directly in the
 * database, which a trigger alone cannot prevent.
 *
 * <p>How: Each row's hash covers its content and the previous hash. An advisory lock serializes
 * writers until commit, so the chain has one order.
 *
 * <p>How to use: Call inside the transaction of the action, so both commit together. Do not record
 * secrets.
 *
 * <p>Safety: The lock makes audited transactions run one at a time. That suits low-volume
 * privileged actions, so do not use this for every payment event.
 *
 * <p>Tests: {@code AuditLogIntegrationTest}.
 */
@Service
public class AuditLog {

  private static final String GENESIS = "0".repeat(64);
  private static final long CHAIN_LOCK = 7_291_001L;

  private final JdbcClient jdbc;
  private final Clock clock;

  public AuditLog(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** One audit event. */
  public record Entry(
      long id,
      String actor,
      String action,
      String resourceType,
      String resourceId,
      String correlationId,
      Instant createdAt,
      String previousHash,
      String hash) {}

  @Transactional
  public void record(String actor, String action, String resourceType, String resourceId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(CHAIN_LOCK).query(Object.class).list();
    String previous =
        jdbc.sql("SELECT hash FROM audit_events ORDER BY id DESC LIMIT 1")
            .query(String.class)
            .optional()
            .orElse(GENESIS);
    // PostgreSQL stores microseconds, so the hashed value must match what is read back.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    String hash = hash(previous, actor, action, resourceType, resourceId, correlationId, now);
    jdbc.sql(
            """
            INSERT INTO audit_events
              (actor, action, resource_type, resource_id, correlation_id, created_at,
               previous_hash, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            actor,
            action,
            resourceType,
            resourceId,
            correlationId,
            Timestamp.from(now),
            previous,
            hash)
        .update();
  }

  /** Returns the newest events first. */
  @Transactional(readOnly = true)
  public List<Entry> list(int page, int size) {
    return jdbc.sql(
            """
            SELECT * FROM audit_events ORDER BY id DESC LIMIT ? OFFSET ?
            """)
        .params(size, (long) page * size)
        .query(AuditLog::map)
        .list();
  }

  /** Checks the whole chain, and returns false if any row was changed or removed. */
  @Transactional(readOnly = true)
  public boolean verify() {
    List<Entry> entries =
        jdbc.sql("SELECT * FROM audit_events ORDER BY id").query(AuditLog::map).list();
    String previous = GENESIS;
    for (Entry e : entries) {
      String expected =
          hash(
              previous,
              e.actor(),
              e.action(),
              e.resourceType(),
              e.resourceId(),
              e.correlationId(),
              e.createdAt());
      if (!previous.equals(e.previousHash()) || !expected.equals(e.hash())) {
        return false;
      }
      previous = e.hash();
    }
    return true;
  }

  private static Entry map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Entry(
        rs.getLong("id"),
        rs.getString("actor"),
        rs.getString("action"),
        rs.getString("resource_type"),
        rs.getString("resource_id"),
        rs.getString("correlation_id"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("previous_hash"),
        rs.getString("hash"));
  }

  private static String hash(
      String previous,
      String actor,
      String action,
      String resourceType,
      String resourceId,
      String correlationId,
      Instant createdAt) {
    // A separator that cannot appear in these values keeps the fields from running together.
    return Hashing.sha256Hex(
        String.join(
            "\u0000",
            previous,
            actor,
            action,
            resourceType,
            resourceId,
            String.valueOf(correlationId),
            createdAt.toString()));
  }
}
