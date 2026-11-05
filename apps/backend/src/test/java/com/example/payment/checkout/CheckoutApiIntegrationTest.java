package com.example.payment.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.AbstractIntegrationTest;
import com.example.payment.common.Hashing;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CheckoutApiIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/checkout-sessions";
  private static final String VALID =
      "{\"merchantOrderId\":\"ORD-1\",\"amount\":250000,\"currency\":\"IDR\"}";

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  private UUID merchantA;
  private String keyA;
  private String keyB;
  private String operatorKey;

  @BeforeEach
  void createClients() {
    merchantA = createMerchant();
    keyA = createClient(merchantA, "MERCHANT");
    keyB = createClient(createMerchant(), "MERCHANT");
    operatorKey = createClient(null, "OPERATOR");
  }

  private UUID createMerchant() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, ?)").params(id, "Test merchant").update();
    return id;
  }

  private String createClient(UUID merchantId, String role) {
    String key = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO api_clients (id, merchant_id, role, key_hash) VALUES (?, ?, ?, ?)")
        .params(UUID.randomUUID(), merchantId, role, Hashing.sha256Hex(key))
        .update();
    return key;
  }

  private static String newIdempotencyKey() {
    return UUID.randomUUID().toString();
  }

  private HttpResponse<String> create(String apiKey, String idempotencyKey, String body)
      throws Exception {
    return post(PATH, Map.of("X-Api-Key", apiKey, "Idempotency-Key", idempotencyKey), body);
  }

  private JsonNode created(String body) throws Exception {
    HttpResponse<String> response = create(keyA, newIdempotencyKey(), body);
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private void assertRejected(String body, String field) throws Exception {
    HttpResponse<String> response = create(keyA, newIdempotencyKey(), body);
    assertEquals(400, response.statusCode(), response.body());
    if (field != null) {
      JsonNode error = json.readTree(response.body());
      assertEquals(field, error.get("fieldErrors").get(0).get("field").asString());
    }
  }

  @Test
  void createsCheckout() throws Exception {
    JsonNode checkout = created(VALID);
    assertEquals(250000, checkout.get("amount").asLong());
    assertEquals("OPEN", checkout.get("status").asString());
    assertTrue(checkout.get("sandbox").asBoolean());
    assertEquals(2, checkout.get("allowedChannels").size());
    assertTrue(
        checkout
            .get("checkoutUrl")
            .asString()
            .startsWith("http://checkout.test/checkout/" + checkout.get("checkoutId").asString()));
  }

  @Test
  void requiresValidApiKey() throws Exception {
    assertEquals(401, post(PATH, Map.of("Idempotency-Key", "abcdefgh"), VALID).statusCode());
    assertEquals(401, create("wrong-key", newIdempotencyKey(), VALID).statusCode());
  }

  @Test
  void staffMayNotCreateCheckout() throws Exception {
    assertEquals(403, create(operatorKey, newIdempotencyKey(), VALID).statusCode());
  }

  @Test
  void requiresWellFormedIdempotencyKey() throws Exception {
    assertEquals(400, post(PATH, Map.of("X-Api-Key", keyA), VALID).statusCode());
    assertEquals(400, create(keyA, "short", VALID).statusCode());
  }

  @Test
  void rejectsClientSuppliedStatus() throws Exception {
    assertRejected(
        "{\"merchantOrderId\":\"O\",\"amount\":1,\"currency\":\"IDR\",\"status\":\"PAID\"}", null);
  }

  @Test
  void rejectsInvalidValues() throws Exception {
    assertRejected("{\"merchantOrderId\":\"O\",\"amount\":-1,\"currency\":\"IDR\"}", "amount");
    assertRejected("{\"merchantOrderId\":\"O\",\"amount\":0,\"currency\":\"IDR\"}", "amount");
    assertRejected("{\"merchantOrderId\":\"O\",\"amount\":1,\"currency\":\"USD\"}", "currency");
    assertRejected(
        "{\"merchantOrderId\":\"O\",\"amount\":100000001,\"currency\":\"IDR\"}", "amount");
    assertRejected(
        "{\"merchantOrderId\":\"a b\",\"amount\":1,\"currency\":\"IDR\"}", "merchantOrderId");
    assertRejected(
        "{\"merchantOrderId\":\"O\",\"amount\":1,\"currency\":\"IDR\",\"customerReference\":\"a@b.example\"}",
        "customerReference");
    assertRejected(
        "{\"merchantOrderId\":\"O\",\"amount\":1,\"currency\":\"IDR\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}",
        "expiresAt");
    assertRejected(
        "{\"merchantOrderId\":\"O\",\"amount\":1,\"currency\":\"IDR\",\"metadata\":{\"pan\":\"x\"}}",
        "metadata");
  }

  @Test
  void replayReturnsSameCheckout() throws Exception {
    String key = newIdempotencyKey();
    HttpResponse<String> first = create(keyA, key, VALID);
    HttpResponse<String> second = create(keyA, key, VALID);
    assertEquals(201, second.statusCode());
    assertEquals("false", first.headers().firstValue("Idempotency-Replayed").orElseThrow());
    assertEquals("true", second.headers().firstValue("Idempotency-Replayed").orElseThrow());
    assertEquals(
        json.readTree(first.body()).get("checkoutId"),
        json.readTree(second.body()).get("checkoutId"));
    assertEquals(
        json.readTree(first.body()).get("checkoutUrl"),
        json.readTree(second.body()).get("checkoutUrl"));
  }

  @Test
  void sameKeyWithDifferentBodyConflicts() throws Exception {
    String key = newIdempotencyKey();
    create(keyA, key, VALID);
    HttpResponse<String> conflict = create(keyA, key, VALID.replace("250000", "250001"));
    assertEquals(409, conflict.statusCode());
    assertEquals("IDEMPOTENCY_KEY_CONFLICT", json.readTree(conflict.body()).get("code").asString());
  }

  @Test
  void rejectedRequestDoesNotConsumeKey() throws Exception {
    String key = newIdempotencyKey();
    String invalid = VALID.replace("IDR", "USD");
    assertEquals(400, create(keyA, key, invalid).statusCode());
    assertEquals(400, create(keyA, key, invalid).statusCode());
    assertEquals(201, create(keyA, key, VALID).statusCode());
  }

  @Test
  void keysAreScopedToMerchant() throws Exception {
    String key = newIdempotencyKey();
    JsonNode a = json.readTree(create(keyA, key, VALID).body());
    JsonNode b = json.readTree(create(keyB, key, VALID).body());
    assertNotEquals(a.get("checkoutId"), b.get("checkoutId"));
  }

  @Test
  void concurrentRepeatsCreateOneCheckout() throws Exception {
    String key = newIdempotencyKey();
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return create(keyA, key, VALID);
            }));
      }
      start.countDown();
      Set<String> ids = new HashSet<>();
      int replays = 0;
      for (Future<HttpResponse<String>> future : futures) {
        HttpResponse<String> response = future.get();
        assertEquals(201, response.statusCode(), response.body());
        ids.add(json.readTree(response.body()).get("checkoutId").asString());
        if (response.headers().firstValue("Idempotency-Replayed").orElseThrow().equals("true")) {
          replays++;
        }
      }
      assertEquals(1, ids.size());
      assertEquals(threads - 1, replays);
    }
    int stored =
        jdbc.sql("SELECT count(*) FROM checkout_sessions WHERE merchant_id = ?")
            .param(merchantA)
            .query(Integer.class)
            .single();
    assertEquals(1, stored);
  }

  @Test
  void merchantReadsOnlyItsOwnCheckout() throws Exception {
    String id = created(VALID).get("checkoutId").asString();
    assertEquals(200, get(PATH + "/" + id, Map.of("X-Api-Key", keyA)).statusCode());
    assertEquals(404, get(PATH + "/" + id, Map.of("X-Api-Key", keyB)).statusCode());
    assertEquals(200, get(PATH + "/" + id, Map.of("X-Api-Key", operatorKey)).statusCode());
  }

  @Test
  void customerTokenGivesLimitedView() throws Exception {
    JsonNode checkout =
        created(
            "{\"merchantOrderId\":\"O\",\"amount\":1,\"currency\":\"IDR\","
                + "\"customerReference\":\"cust-1\",\"metadata\":{\"note\":\"x\"}}");
    String id = checkout.get("checkoutId").asString();
    String token = checkout.get("checkoutUrl").asString().split("#token=")[1];

    HttpResponse<String> response = get(PATH + "/" + id, Map.of("X-Checkout-Token", token));
    assertEquals(200, response.statusCode());
    JsonNode view = json.readTree(response.body());
    assertEquals(1, view.get("amount").asLong());
    assertFalse(view.has("customerReference"));
    assertFalse(view.has("metadata"));
    assertFalse(view.has("checkoutUrl"));
  }

  @Test
  void customerWithoutValidTokenSeesNotFound() throws Exception {
    JsonNode a = created(VALID);
    JsonNode b = created(VALID);
    String tokenOfB = b.get("checkoutUrl").asString().split("#token=")[1];
    String path = PATH + "/" + a.get("checkoutId").asString();

    assertEquals(404, get(path, new HashMap<>()).statusCode());
    assertEquals(404, get(path, Map.of("X-Checkout-Token", "wrong")).statusCode());
    assertEquals(404, get(path, Map.of("X-Checkout-Token", tokenOfB)).statusCode());
  }

  @Test
  void pastExpiryIsReportedAsExpired() throws Exception {
    String id = created(VALID).get("checkoutId").asString();
    jdbc.sql("UPDATE checkout_sessions SET expires_at = ? WHERE id = ?::uuid")
        .params(java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id)
        .update();
    HttpResponse<String> response = get(PATH + "/" + id, Map.of("X-Api-Key", keyA));
    assertEquals("EXPIRED", json.readTree(response.body()).get("status").asString());
  }

  @Test
  void databaseRejectsInvalidAmountEvenIfCodeFails() {
    org.junit.jupiter.api.Assertions.assertThrows(
        Exception.class,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO checkout_sessions
                      (id, merchant_id, merchant_order_id, amount, currency, expires_at, created_at)
                    VALUES (?, ?, 'O', -1, 'IDR', now(), now())
                    """)
                .params(UUID.randomUUID(), merchantA)
                .update());
  }
}
