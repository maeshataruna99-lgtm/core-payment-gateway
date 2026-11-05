package com.example.payment.webhook;

import com.example.payment.common.DomainException;
import com.example.payment.merchant.ApiClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: REST API for reading webhook deliveries, and UC-07, retrying a failed one.
 *
 * <p>How to use: A merchant sees its own deliveries. Staff see all of them.
 *
 * <p>Safety: Only a {@code FAILED} delivery can be retried, so a delivered event is never sent
 * again by hand. {@code maxTotalAttempts} limits the retries.
 *
 * <p>Tests: {@code WebhookIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/webhook-deliveries")
class WebhookDeliveryController {

  private static final String COLUMNS =
      "id, endpoint_id, outbox_event_id, merchant_id, event_type, state, attempts, next_attempt_at,"
          + " created_at, delivered_at";
  private static final RowMapper<Delivery> MAPPER =
      (rs, row) ->
          new Delivery(
              rs.getObject("id", UUID.class),
              rs.getObject("endpoint_id", UUID.class),
              rs.getObject("outbox_event_id", UUID.class),
              rs.getObject("merchant_id", UUID.class),
              rs.getString("event_type"),
              rs.getString("state"),
              rs.getInt("attempts"),
              rs.getTimestamp("next_attempt_at").toInstant(),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("delivered_at") == null
                  ? null
                  : rs.getTimestamp("delivered_at").toInstant(),
              null);

  private final JdbcClient jdbc;
  private final WebhookProperties properties;
  private final Clock clock;

  WebhookDeliveryController(JdbcClient jdbc, WebhookProperties properties, Clock clock) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.clock = clock;
  }

  /** A webhook delivery. {@code history} is set when reading one delivery. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Delivery(
      UUID id,
      UUID endpointId,
      UUID eventId,
      UUID merchantId,
      String eventType,
      String state,
      int attempts,
      Instant nextAttemptAt,
      Instant createdAt,
      Instant deliveredAt,
      List<AttemptRecord> history) {}

  /** The result of one send. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record AttemptRecord(int attempt, Integer statusCode, String error, Instant at) {}

  /** One page of deliveries, newest first. */
  record Page(List<Delivery> items, int page, int size) {}

  @Operation(summary = "List webhook deliveries", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping
  Page list(
      @AuthenticationPrincipal ApiClient client,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
    // Staff clients have no merchant ID, so they see every merchant's deliveries.
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM webhook_deliveries WHERE (?::uuid IS NULL OR merchant_id = ?)"
            + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
    List<Delivery> items =
        jdbc.sql(sql)
            .params(client.merchantId(), client.merchantId(), size, (long) page * size)
            .query(MAPPER)
            .list();
    return new Page(items, page, size);
  }

  @Operation(summary = "Read a webhook delivery", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/{deliveryId}")
  Delivery get(@AuthenticationPrincipal ApiClient client, @PathVariable UUID deliveryId) {
    Delivery delivery = find(client, deliveryId);
    List<AttemptRecord> history =
        jdbc.sql(
                """
                SELECT attempt, status_code, error, created_at FROM webhook_delivery_attempts
                WHERE delivery_id = ? ORDER BY id
                """)
            .param(deliveryId)
            .query((rs, row) ->
                new AttemptRecord(
                    rs.getInt("attempt"),
                    rs.getObject("status_code", Integer.class),
                    rs.getString("error"),
                    rs.getTimestamp("created_at").toInstant()))
            .list();
    return new Delivery(
        delivery.id(),
        delivery.endpointId(),
        delivery.eventId(),
        delivery.merchantId(),
        delivery.eventType(),
        delivery.state(),
        delivery.attempts(),
        delivery.nextAttemptAt(),
        delivery.createdAt(),
        delivery.deliveredAt(),
        history);
  }

  @Operation(
      summary = "Retry a failed webhook delivery",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping("/{deliveryId}/retry")
  Delivery retry(@AuthenticationPrincipal ApiClient client, @PathVariable UUID deliveryId) {
    find(client, deliveryId);
    int changed =
        jdbc.sql(
                """
                UPDATE webhook_deliveries SET state = 'PENDING', next_attempt_at = ?
                WHERE id = ? AND state = 'FAILED' AND attempts < ?
                """)
            .params(Timestamp.from(clock.instant()), deliveryId, properties.maxTotalAttempts())
            .update();
    Delivery current = find(client, deliveryId);
    if (changed == 0) {
      boolean limitReached =
          "FAILED".equals(current.state()) && current.attempts() >= properties.maxTotalAttempts();
      throw new DomainException(
          HttpStatus.CONFLICT,
          limitReached ? "RETRY_LIMIT_REACHED" : "DELIVERY_NOT_RETRYABLE",
          limitReached
              ? "This delivery has reached its retry limit."
              : "Only a failed delivery can be retried.");
    }
    return current;
  }

  private Delivery find(ApiClient client, UUID deliveryId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_deliveries WHERE id = ?")
        .param(deliveryId)
        .query(MAPPER)
        .optional()
        .filter(d ->
            client.role() != ApiClient.Role.MERCHANT || d.merchantId().equals(client.merchantId()))
        .orElseThrow(
            () -> new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Delivery not found."));
  }
}
