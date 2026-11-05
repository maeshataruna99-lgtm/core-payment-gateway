package com.example.payment.common;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * What: Records a domain event in the outbox.
 *
 * <p>Why: If the event were published directly, a crash could lose it or announce a change that
 * never committed.
 *
 * <p>How to use: Call inside the transaction that makes the change. It fails without one.
 *
 * <p>Tests: {@code WebhookIntegrationTest}.
 */
@Component
public class OutboxWriter {

  private final JdbcClient jdbc;
  private final JsonMapper mapper;
  private final Clock clock;

  public OutboxWriter(JdbcClient jdbc, JsonMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void write(String type, UUID merchantId, Map<String, Object> payload) {
    jdbc.sql(
            """
            INSERT INTO outbox_events (id, type, merchant_id, payload, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            """)
        .params(
            UUID.randomUUID(),
            type,
            merchantId,
            mapper.writeValueAsString(payload),
            Timestamp.from(clock.instant()))
        .update();
  }
}
