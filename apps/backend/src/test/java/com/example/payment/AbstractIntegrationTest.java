package com.example.payment;

import com.example.payment.common.Hashing;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the whole application against one real PostgreSQL shared by all tests. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort int port;

  protected HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)));
    headers.forEach(request::header);
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  protected HttpResponse<String> post(String path, Map<String, String> headers, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  protected UUID createMerchant(JdbcClient jdbc) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, 'Test')").param(id).update();
    return id;
  }

  protected String createClient(JdbcClient jdbc, UUID merchantId, String role) {
    String key = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO api_clients (id, merchant_id, role, key_hash) VALUES (?, ?, ?, ?)")
        .params(UUID.randomUUID(), merchantId, role, Hashing.sha256Hex(key))
        .update();
    return key;
  }

  protected JsonNode createCheckout(JsonMapper json, String merchantKey, long amount)
      throws Exception {
    return json.readTree(
        post(
                "/api/v1/checkout-sessions",
                Map.of("X-Api-Key", merchantKey, "Idempotency-Key", UUID.randomUUID().toString()),
                "{\"merchantOrderId\":\"O\",\"amount\":" + amount + ",\"currency\":\"IDR\"}")
            .body());
  }

  protected JsonNode startPayment(JsonMapper json, String merchantKey, JsonNode checkout)
      throws Exception {
    return json.readTree(
        post(
                "/api/v1/payments",
                Map.of("X-Api-Key", merchantKey, "Idempotency-Key", UUID.randomUUID().toString()),
                "{\"checkoutId\":\""
                    + checkout.get("checkoutId").asString()
                    + "\",\"paymentMethod\":\"QRIS\"}")
            .body());
  }

  protected void markSucceeded(JsonNode payment, String operatorKey) throws Exception {
    post(
        "/api/v1/sandbox/payments/" + payment.get("paymentId").asString() + "/events",
        Map.of("X-Api-Key", operatorKey),
        "{\"outcome\":\"SUCCESS\"}");
  }

  /** Creates a checkout and pays it. */
  protected JsonNode paidPayment(
      JsonMapper json, String merchantKey, String operatorKey, long amount) throws Exception {
    JsonNode payment = startPayment(json, merchantKey, createCheckout(json, merchantKey, amount));
    markSucceeded(payment, operatorKey);
    return payment;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
