package com.example.payment.webhook;

import com.example.payment.audit.AuditLog;
import com.example.payment.common.DomainException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Registers, lists, and disables a merchant's webhook endpoints (UC-06).
 *
 * <p>How: Generates a random signing secret, stores it encrypted, and returns it only once.
 *
 * <p>Safety: Every read and change is scoped to the merchant.
 *
 * <p>Tests: {@code WebhookIntegrationTest}.
 */
@Service
class WebhookEndpointService {

  static final Set<String> EVENT_TYPES =
      Set.of(
          "payment.succeeded",
          "payment.failed",
          "payment.expired",
          "refund.succeeded",
          "refund.failed",
          "settlement.finalized");

  private final JdbcClient jdbc;
  private final SecretCipher cipher;
  private final WebhookUrlPolicy urlPolicy;
  private final AuditLog audit;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  WebhookEndpointService(
      JdbcClient jdbc,
      SecretCipher cipher,
      WebhookUrlPolicy urlPolicy,
      AuditLog audit,
      Clock clock) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.urlPolicy = urlPolicy;
    this.audit = audit;
    this.clock = clock;
  }

  /** An endpoint as returned by the API. {@code secret} is set only on creation. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Endpoint(
      UUID id, String url, List<String> events, boolean active, Instant createdAt, String secret) {}

  @Transactional
  public Endpoint register(UUID merchantId, String actor, String url, List<String> events) {
    urlPolicy
        .rejectionReason(url)
        .ifPresent(reason -> {
          throw DomainException.invalidField("url", reason);
        });
    if (!EVENT_TYPES.containsAll(events)) {
      throw DomainException.invalidField("events", "An event type is not supported.");
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    jdbc.sql(
            """
            INSERT INTO webhook_endpoints (id, merchant_id, url, secret_encrypted, active, created_at)
            VALUES (?, ?, ?, ?, true, ?)
            """)
        .params(id, merchantId, url, cipher.encrypt(secret), Timestamp.from(now))
        .update();
    List<String> unique = events.stream().distinct().toList();
    for (String event : unique) {
      jdbc.sql("INSERT INTO webhook_endpoint_events (endpoint_id, event_type) VALUES (?, ?)")
          .params(id, event)
          .update();
    }
    audit.record(actor, "webhook.endpoint.registered", "webhook_endpoint", id.toString());
    return new Endpoint(id, url, unique, true, now, secret);
  }

  @Transactional(readOnly = true)
  public List<Endpoint> list(UUID merchantId) {
    return jdbc.sql(
            """
            SELECT e.id, e.url, e.active, e.created_at,
              (SELECT string_agg(ev.event_type, ',' ORDER BY ev.event_type)
               FROM webhook_endpoint_events ev WHERE ev.endpoint_id = e.id) AS events
            FROM webhook_endpoints e WHERE e.merchant_id = ? ORDER BY e.created_at DESC
            """)
        .param(merchantId)
        .query((rs, row) ->
            new Endpoint(
                rs.getObject("id", UUID.class),
                rs.getString("url"),
                List.of(rs.getString("events").split(",")),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                null))
        .list();
  }

  @Transactional
  public void disable(UUID merchantId, String actor, UUID endpointId) {
    int changed =
        jdbc.sql("UPDATE webhook_endpoints SET active = false WHERE id = ? AND merchant_id = ?")
            .params(endpointId, merchantId)
            .update();
    if (changed == 0) {
      throw new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Endpoint not found.");
    }
    audit.record(actor, "webhook.endpoint.disabled", "webhook_endpoint", endpointId.toString());
  }
}
