package com.example.payment.webhook;

import com.example.payment.common.HmacSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What: Sends due webhook deliveries, signs them, and schedules retries.
 *
 * <p>Why: A merchant's server can be down. At-least-once delivery with backoff recovers without any
 * effect on the payment.
 *
 * <p>How: Sends outside any transaction, then records the attempt. A failure retries after {@code
 * baseDelay * 2^(attempts - 1)} until {@code maxAutomaticAttempts}, then becomes {@code FAILED}.
 *
 * <p>How to use: Merchants verify {@code X-PayGate-Signature} as {@code t=<epoch
 * seconds>,v1=<hex>}, the HMAC-SHA256 of {@code t.body}, and deduplicate on {@code
 * X-PayGate-Event-Id}. Set {@code payment.webhook.deliver-cron}. The value {@code -} turns it off.
 *
 * <p>Safety: Redirects are not followed. The URL is checked again before every send. Secrets and
 * response bodies are never stored or logged.
 *
 * <p>Tests: {@code WebhookIntegrationTest}.
 */
// shortcut: sends one at a time and assumes one running instance; use a worker pool and row claims
// if slow merchants start delaying others or the service ever runs as several instances.
@Component
class WebhookDeliverer {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliverer.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
  private static final int BATCH = 20;

  private record Due(
      UUID id,
      UUID eventId,
      String eventType,
      String payload,
      int attempts,
      String url,
      String secretEncrypted,
      boolean active) {}

  private record SendResult(Integer statusCode, String error) {
    boolean delivered() {
      return statusCode != null && statusCode >= 200 && statusCode < 300;
    }
  }

  private final JdbcClient jdbc;
  private final SecretCipher cipher;
  private final WebhookUrlPolicy urlPolicy;
  private final WebhookProperties properties;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  WebhookDeliverer(
      JdbcClient jdbc,
      SecretCipher cipher,
      WebhookUrlPolicy urlPolicy,
      WebhookProperties properties,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.urlPolicy = urlPolicy;
    this.properties = properties;
    this.transaction = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Scheduled(cron = "${payment.webhook.deliver-cron}")
  void run() {
    List<Due> due =
        jdbc.sql(
                """
                SELECT d.id, d.outbox_event_id, d.event_type, d.payload, d.attempts,
                       e.url, e.secret_encrypted, e.active
                FROM webhook_deliveries d JOIN webhook_endpoints e ON e.id = d.endpoint_id
                WHERE d.state = 'PENDING' AND d.next_attempt_at <= ?
                ORDER BY d.next_attempt_at LIMIT ?
                """)
            .params(Timestamp.from(clock.instant()), BATCH)
            .query((rs, row) ->
                new Due(
                    rs.getObject("id", UUID.class),
                    rs.getObject("outbox_event_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getInt("attempts"),
                    rs.getString("url"),
                    rs.getString("secret_encrypted"),
                    rs.getBoolean("active")))
            .list();
    due.forEach(this::deliverSafely);
  }

  private void deliverSafely(Due delivery) {
    try {
      if (!delivery.active()) {
        jdbc.sql("UPDATE webhook_deliveries SET state = 'CANCELLED' WHERE id = ?")
            .param(delivery.id())
            .update();
        return;
      }
      SendResult result = send(delivery);
      transaction.executeWithoutResult(status -> record(delivery, result));
    } catch (RuntimeException e) {
      LOG.error("Could not process webhook delivery {}", delivery.id(), e);
    }
  }

  private SendResult send(Due delivery) {
    Optional<String> rejected = urlPolicy.rejectionReason(delivery.url());
    if (rejected.isPresent()) {
      return new SendResult(null, rejected.get());
    }
    long timestamp = clock.instant().getEpochSecond();
    String secret = cipher.decrypt(delivery.secretEncrypted());
    String signature = HmacSigner.sign(secret, timestamp, delivery.payload());
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(delivery.url()))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("X-PayGate-Event-Id", delivery.eventId().toString())
            .header("X-PayGate-Event-Type", delivery.eventType())
            .header("X-PayGate-Signature", "t=" + timestamp + ",v1=" + signature)
            .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
            .build();
    try {
      int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
      return new SendResult(status, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new SendResult(null, "Interrupted");
    } catch (Exception e) {
      return new SendResult(null, e.getClass().getSimpleName());
    }
  }

  private void record(Due delivery, SendResult result) {
    int attempt = delivery.attempts() + 1;
    Instant now = clock.instant();
    Timestamp nowStamp = Timestamp.from(now);
    jdbc.sql(
            """
            INSERT INTO webhook_delivery_attempts
              (delivery_id, attempt, status_code, error, created_at)
            VALUES (?, ?, ?, ?, ?)
            """)
        .params(delivery.id(), attempt, result.statusCode(), result.error(), nowStamp)
        .update();
    if (result.delivered()) {
      jdbc.sql(
              """
              UPDATE webhook_deliveries SET state = 'DELIVERED', attempts = ?, delivered_at = ?
              WHERE id = ?
              """)
          .params(attempt, nowStamp, delivery.id())
          .update();
    } else if (attempt >= properties.maxAutomaticAttempts()) {
      jdbc.sql("UPDATE webhook_deliveries SET state = 'FAILED', attempts = ? WHERE id = ?")
          .params(attempt, delivery.id())
          .update();
    } else {
      Duration delay = properties.baseDelay().multipliedBy(1L << (attempt - 1));
      jdbc.sql("UPDATE webhook_deliveries SET attempts = ?, next_attempt_at = ? WHERE id = ?")
          .params(attempt, Timestamp.from(now.plus(delay)), delivery.id())
          .update();
    }
  }
}
