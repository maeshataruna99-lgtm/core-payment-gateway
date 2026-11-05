package com.example.payment.webhook;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What: Turns unpublished outbox events into webhook deliveries.
 *
 * <p>Why: Payment code only writes to the outbox, so a slow merchant can never affect a payment
 * transaction.
 *
 * <p>How: Locks a batch with {@code SKIP LOCKED}, creates one delivery per subscribed endpoint, and
 * marks the event published, all in one transaction. A unique constraint prevents a second delivery
 * for the same endpoint and event.
 *
 * <p>How to use: Set {@code payment.webhook.publish-cron}. The value {@code -} turns it off.
 *
 * <p>Tests: {@code WebhookIntegrationTest}.
 */
@Component
class OutboxPublisher {

  private record Event(UUID id, String type, UUID merchantId, String payload, Instant createdAt) {}

  private final JdbcClient jdbc;
  private final JsonMapper mapper;
  private final Clock clock;

  OutboxPublisher(JdbcClient jdbc, JsonMapper mapper, Clock clock) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Scheduled(cron = "${payment.webhook.publish-cron}")
  @Transactional
  public void publish() {
    List<Event> events =
        jdbc.sql(
                """
                SELECT id, type, merchant_id, payload::text AS payload, created_at
                FROM outbox_events WHERE published_at IS NULL
                ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """)
            .query((rs, row) ->
                new Event(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    rs.getObject("merchant_id", UUID.class),
                    rs.getString("payload"),
                    rs.getTimestamp("created_at").toInstant()))
            .list();
    Timestamp now = Timestamp.from(clock.instant());
    for (Event event : events) {
      List<UUID> endpoints =
          jdbc.sql(
                  """
                  SELECT e.id FROM webhook_endpoints e
                  JOIN webhook_endpoint_events s ON s.endpoint_id = e.id
                  WHERE e.merchant_id = ? AND e.active AND s.event_type = ?
                  """)
              .params(event.merchantId(), event.type())
              .query(UUID.class)
              .list();
      String body = envelope(event);
      for (UUID endpoint : endpoints) {
        jdbc.sql(
                """
                INSERT INTO webhook_deliveries
                  (id, endpoint_id, outbox_event_id, merchant_id, event_type, payload, state,
                   next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                ON CONFLICT (endpoint_id, outbox_event_id) DO NOTHING
                """)
            .params(
                UUID.randomUUID(),
                endpoint,
                event.id(),
                event.merchantId(),
                event.type(),
                body,
                now,
                now)
            .update();
      }
      jdbc.sql("UPDATE outbox_events SET published_at = ? WHERE id = ?")
          .params(now, event.id())
          .update();
    }
  }

  private String envelope(Event event) {
    JsonNode data = mapper.readTree(event.payload());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", event.id());
    body.put("type", event.type());
    body.put("createdAt", event.createdAt());
    body.put("sandbox", true);
    body.put("data", data);
    return mapper.writeValueAsString(body);
  }
}
