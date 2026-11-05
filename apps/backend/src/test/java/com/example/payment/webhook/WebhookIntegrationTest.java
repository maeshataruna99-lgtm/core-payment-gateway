package com.example.payment.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.AbstractIntegrationTest;
import com.example.payment.common.Hashing;
import com.example.payment.common.HmacSigner;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WebhookIntegrationTest extends AbstractIntegrationTest {

  private static final String ENDPOINTS = "/api/v1/webhook-endpoints";
  private static final String DELIVERIES = "/api/v1/webhook-deliveries";
  private static final String ALL_EVENTS =
      "[\"payment.succeeded\",\"payment.failed\",\"payment.expired\"]";

  /** One request the fake merchant server received. */
  private record Received(String body, String eventId, String signature) {}

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired OutboxPublisher publisher;
  @Autowired WebhookDeliverer deliverer;

  private HttpServer merchantServer;
  private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
  private final Deque<Integer> responses = new ArrayDeque<>();
  private String keyA;
  private String keyB;
  private String operatorKey;

  @BeforeEach
  void setUp() throws Exception {
    keyA = createClient(createMerchant(), "MERCHANT");
    keyB = createClient(createMerchant(), "MERCHANT");
    operatorKey = createClient(null, "OPERATOR");
    merchantServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    merchantServer.createContext(
        "/hook",
        exchange -> {
          received.add(
              new Received(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("X-PayGate-Event-Id"),
                  exchange.getRequestHeaders().getFirst("X-PayGate-Signature")));
          int status;
          synchronized (responses) {
            status = responses.isEmpty() ? 200 : responses.poll();
          }
          if (status == 302) {
            exchange.getResponseHeaders().add("Location", hookUrl() + "/redirected");
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    merchantServer.start();
  }

  @AfterEach
  void stopServer() {
    merchantServer.stop(0);
  }

  private String hookUrl() {
    return "http://localhost:" + merchantServer.getAddress().getPort() + "/hook";
  }

  private void respondWith(Integer... statuses) {
    synchronized (responses) {
      responses.addAll(List.of(statuses));
    }
  }

  private UUID createMerchant() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, 'M')").param(id).update();
    return id;
  }

  private String createClient(UUID merchantId, String role) {
    String key = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO api_clients (id, merchant_id, role, key_hash) VALUES (?, ?, ?, ?)")
        .params(UUID.randomUUID(), merchantId, role, Hashing.sha256Hex(key))
        .update();
    return key;
  }

  private JsonNode register(String key, String events) throws Exception {
    HttpResponse<String> response =
        post(
            ENDPOINTS,
            Map.of("X-Api-Key", key),
            "{\"url\":\"" + hookUrl() + "\",\"events\":" + events + "}");
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private JsonNode pay() throws Exception {
    JsonNode checkout =
        json.readTree(
            post(
                    "/api/v1/checkout-sessions",
                    Map.of("X-Api-Key", keyA, "Idempotency-Key", UUID.randomUUID().toString()),
                    "{\"merchantOrderId\":\"O\",\"amount\":1000,\"currency\":\"IDR\"}")
                .body());
    return json.readTree(
        post(
                "/api/v1/payments",
                Map.of("X-Api-Key", keyA, "Idempotency-Key", UUID.randomUUID().toString()),
                "{\"checkoutId\":\""
                    + checkout.get("checkoutId").asString()
                    + "\",\"paymentMethod\":\"QRIS\"}")
            .body());
  }

  private void answer(JsonNode payment, String outcome) throws Exception {
    HttpResponse<String> response =
        post(
            "/api/v1/sandbox/payments/" + payment.get("paymentId").asString() + "/events",
            Map.of("X-Api-Key", operatorKey),
            "{\"outcome\":\"" + outcome + "\"}");
    assertEquals(200, response.statusCode(), response.body());
  }

  /** Registers an endpoint, pays, and returns the delivery created for the payment. */
  private JsonNode paidDelivery() throws Exception {
    register(keyA, ALL_EVENTS);
    answer(pay(), "SUCCESS");
    publisher.publish();
    return json.readTree(get(DELIVERIES, Map.of("X-Api-Key", keyA)).body()).get("items").get(0);
  }

  private JsonNode readDelivery(JsonNode delivery) throws Exception {
    return json.readTree(
        get(DELIVERIES + "/" + delivery.get("id").asString(), Map.of("X-Api-Key", keyA)).body());
  }

  private HttpResponse<String> retry(String key, JsonNode delivery) throws Exception {
    return post(
        DELIVERIES + "/" + delivery.get("id").asString() + "/retry", Map.of("X-Api-Key", key), "");
  }

  @Test
  void showsSecretOnceAndStoresItEncrypted() throws Exception {
    JsonNode endpoint = register(keyA, ALL_EVENTS);
    String secret = endpoint.get("secret").asString();
    assertTrue(secret.startsWith("whsec_"));
    assertFalse(endpoint.get("url").isNull());

    HttpResponse<String> list = get(ENDPOINTS, Map.of("X-Api-Key", keyA));
    assertFalse(list.body().contains("secret"));
    assertEquals(1, json.readTree(list.body()).size());

    String stored =
        jdbc.sql("SELECT secret_encrypted FROM webhook_endpoints WHERE id = ?::uuid")
            .param(endpoint.get("id").asString())
            .query(String.class)
            .single();
    assertFalse(stored.contains(secret));
  }

  @Test
  void rejectsUnsafeOrInvalidEndpoints() throws Exception {
    for (String url :
        List.of("http://example.com/x", "https://127.0.0.1/x", "https://169.254.169.254/x", "x")) {
      HttpResponse<String> response =
          post(
              ENDPOINTS,
              Map.of("X-Api-Key", keyA),
              "{\"url\":\"" + url + "\",\"events\":" + ALL_EVENTS + "}");
      assertEquals(400, response.statusCode(), url);
    }
    assertEquals(
        400,
        post(
                ENDPOINTS,
                Map.of("X-Api-Key", keyA),
                "{\"url\":\"" + hookUrl() + "\",\"events\":[\"unknown.event\"]}")
            .statusCode());
  }

  @Test
  void onlyMerchantsManageTheirEndpoints() throws Exception {
    assertEquals(
        403,
        post(
                ENDPOINTS,
                Map.of("X-Api-Key", operatorKey),
                "{\"url\":\"" + hookUrl() + "\",\"events\":" + ALL_EVENTS + "}")
            .statusCode());
    JsonNode endpoint = register(keyA, ALL_EVENTS);
    String disable = ENDPOINTS + "/" + endpoint.get("id").asString() + "/disable";
    assertEquals(404, post(disable, Map.of("X-Api-Key", keyB), "").statusCode());
    assertEquals(204, post(disable, Map.of("X-Api-Key", keyA), "").statusCode());
  }

  @Test
  void deliversSignedWebhookOnce() throws Exception {
    JsonNode endpoint = register(keyA, ALL_EVENTS);
    JsonNode payment = pay();
    answer(payment, "SUCCESS");
    publisher.publish();
    deliverer.run();
    deliverer.run();

    assertEquals(1, received.size());
    Received webhook = received.get(0);
    String[] parts = webhook.signature().split(",");
    long timestamp = Long.parseLong(parts[0].substring(2));
    assertTrue(
        HmacSigner.matches(
            endpoint.get("secret").asString(), timestamp, webhook.body(), parts[1].substring(3)));

    JsonNode body = json.readTree(webhook.body());
    assertEquals("payment.succeeded", body.get("type").asString());
    assertTrue(body.get("sandbox").asBoolean());
    assertEquals(webhook.eventId(), body.get("id").asString());
    assertEquals(payment.get("paymentId"), body.get("data").get("paymentId"));
    assertTrue(body.get("data").get("settlesCheckout").asBoolean());
  }

  @Test
  void retriesWithoutChangingPayment() throws Exception {
    respondWith(503, 500);
    register(keyA, ALL_EVENTS);
    JsonNode payment = pay();
    answer(payment, "SUCCESS");
    publisher.publish();
    deliverer.run();
    deliverer.run();
    deliverer.run();

    JsonNode delivery =
        readDelivery(
            json.readTree(get(DELIVERIES, Map.of("X-Api-Key", keyA)).body()).get("items").get(0));
    assertEquals("DELIVERED", delivery.get("state").asString());
    assertEquals(3, delivery.get("attempts").asInt());
    assertEquals(503, delivery.get("history").get(0).get("statusCode").asInt());
    assertEquals(3, received.size());
    assertEquals(1, received.stream().map(Received::eventId).distinct().count());

    String paymentId = payment.get("paymentId").asString();
    int successes =
        jdbc.sql(
                "SELECT count(*) FROM payment_attempt_events WHERE attempt_id = ?::uuid AND event = 'CALLBACK_SUCCESS'")
            .param(paymentId)
            .query(Integer.class)
            .single();
    assertEquals(1, successes);
  }

  @Test
  void waitsBetweenRetries() throws Exception {
    respondWith(503);
    JsonNode delivery = paidDelivery();
    deliverer.run();
    jdbc.sql(
            "UPDATE webhook_deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?::uuid")
        .param(delivery.get("id").asString())
        .update();
    deliverer.run();
    assertEquals(1, received.size());
  }

  @Test
  void failsAfterLimitThenManualRetrySucceeds() throws Exception {
    respondWith(503, 503, 503);
    JsonNode delivery = paidDelivery();
    deliverer.run();
    deliverer.run();
    deliverer.run();
    deliverer.run();
    assertEquals("FAILED", readDelivery(delivery).get("state").asString());
    assertEquals(3, received.size());

    assertEquals(200, retry(keyA, delivery).statusCode());
    deliverer.run();
    JsonNode retried = readDelivery(delivery);
    assertEquals("DELIVERED", retried.get("state").asString());
    assertEquals(4, retried.get("attempts").asInt());
  }

  @Test
  void deliveredWebhookCannotBeRetried() throws Exception {
    JsonNode delivery = paidDelivery();
    deliverer.run();
    HttpResponse<String> response = retry(keyA, delivery);
    assertEquals(409, response.statusCode());
    assertEquals("DELIVERY_NOT_RETRYABLE", json.readTree(response.body()).get("code").asString());
    assertEquals(1, received.size());
  }

  @Test
  void manualRetryHasLimit() throws Exception {
    respondWith(503, 503, 503, 503);
    JsonNode delivery = paidDelivery();
    deliverer.run();
    deliverer.run();
    deliverer.run();
    assertEquals(200, retry(keyA, delivery).statusCode());
    deliverer.run();
    HttpResponse<String> response = retry(keyA, delivery);
    assertEquals(409, response.statusCode());
    assertEquals("RETRY_LIMIT_REACHED", json.readTree(response.body()).get("code").asString());
  }

  @Test
  void redirectIsNotFollowed() throws Exception {
    respondWith(302);
    JsonNode delivery = paidDelivery();
    deliverer.run();
    assertEquals("PENDING", readDelivery(delivery).get("state").asString());
    assertEquals(1, received.size());
  }

  @Test
  void deliveriesAreScopedToMerchant() throws Exception {
    JsonNode delivery = paidDelivery();
    String path = DELIVERIES + "/" + delivery.get("id").asString();
    assertEquals(404, get(path, Map.of("X-Api-Key", keyB)).statusCode());
    assertEquals(404, retry(keyB, delivery).statusCode());
    assertEquals(200, get(path, Map.of("X-Api-Key", operatorKey)).statusCode());
    assertEquals(401, get(path, Map.of()).statusCode());
    assertEquals(
        0, json.readTree(get(DELIVERIES, Map.of("X-Api-Key", keyB)).body()).get("items").size());
    assertTrue(
        json.readTree(get(DELIVERIES, Map.of("X-Api-Key", operatorKey)).body()).get("items").size()
            >= 1);
  }

  @Test
  void onlySubscribedEventsCreateDeliveries() throws Exception {
    register(keyA, "[\"payment.failed\"]");
    answer(pay(), "SUCCESS");
    publisher.publish();
    deliverer.run();
    assertEquals(0, received.size());
  }

  @Test
  void disabledEndpointStopsDelivery() throws Exception {
    JsonNode endpoint = register(keyA, ALL_EVENTS);
    answer(pay(), "SUCCESS");
    publisher.publish();
    post(
        ENDPOINTS + "/" + endpoint.get("id").asString() + "/disable",
        Map.of("X-Api-Key", keyA),
        "");
    deliverer.run();
    assertEquals(0, received.size());
    JsonNode delivery =
        json.readTree(get(DELIVERIES, Map.of("X-Api-Key", keyA)).body()).get("items").get(0);
    assertEquals("CANCELLED", delivery.get("state").asString());
  }

  @Test
  void onlyFinalStatesAreAnnounced() throws Exception {
    register(keyA, ALL_EVENTS);
    JsonNode pending = pay();
    publisher.publish();
    deliverer.run();
    assertEquals(0, received.size());

    answer(pending, "FAILED");
    publisher.publish();
    deliverer.run();
    assertEquals(1, received.size());
    assertEquals("payment.failed", json.readTree(received.get(0).body()).get("type").asString());
  }
}
