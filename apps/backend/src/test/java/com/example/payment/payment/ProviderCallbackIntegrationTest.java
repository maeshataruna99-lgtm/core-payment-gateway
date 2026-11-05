package com.example.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.AbstractIntegrationTest;
import com.example.payment.common.Hashing;
import com.example.payment.common.HmacSigner;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class ProviderCallbackIntegrationTest extends AbstractIntegrationTest {

  private static final String URL = "/api/v1/sandbox/provider-callbacks/sandbox";
  private static final String SECRET = "test-only-callback-secret";
  private static final long AMOUNT = 1000;

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  private UUID merchant;
  private String key;
  private String operatorKey;
  private JsonNode payment;

  @BeforeEach
  void createPendingPayment() throws Exception {
    merchant = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, 'M')").param(merchant).update();
    key = createClient(merchant, "MERCHANT");
    operatorKey = createClient(null, "OPERATOR");
    JsonNode checkout =
        json.readTree(
            post(
                    "/api/v1/checkout-sessions",
                    Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
                    "{\"merchantOrderId\":\"O\",\"amount\":" + AMOUNT + ",\"currency\":\"IDR\"}")
                .body());
    payment =
        json.readTree(
            post(
                    "/api/v1/payments",
                    Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
                    "{\"checkoutId\":\""
                        + checkout.get("checkoutId").asString()
                        + "\",\"paymentMethod\":\"QRIS\"}")
                .body());
  }

  private String createClient(UUID merchantId, String role) {
    String apiKey = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO api_clients (id, merchant_id, role, key_hash) VALUES (?, ?, ?, ?)")
        .params(UUID.randomUUID(), merchantId, role, Hashing.sha256Hex(apiKey))
        .update();
    return apiKey;
  }

  private String body(String eventId) {
    return body(
        eventId,
        merchant.toString(),
        payment.get("providerReference").asString(),
        AMOUNT,
        "IDR",
        "SUCCESS");
  }

  private String body(
      String eventId,
      String merchantId,
      String reference,
      long amount,
      String currency,
      String outcome) {
    return "{\"eventId\":\"%s\",\"paymentId\":\"%s\",\"merchantId\":\"%s\",\"providerReference\":\"%s\",\"outcome\":\"%s\",\"amount\":%d,\"currency\":\"%s\"}"
        .formatted(
            eventId,
            payment.get("paymentId").asString(),
            merchantId,
            reference,
            outcome,
            amount,
            currency);
  }

  private HttpResponse<String> send(String body) throws Exception {
    return send(URL, body, Instant.now().getEpochSecond(), SECRET);
  }

  private HttpResponse<String> send(String url, String body, long timestamp, String secret)
      throws Exception {
    return post(
        url,
        Map.of(
            "X-Provider-Timestamp",
            String.valueOf(timestamp),
            "X-Provider-Signature",
            HmacSigner.sign(secret, timestamp, body)),
        body);
  }

  private String paymentStatus() throws Exception {
    return json.readTree(
            get("/api/v1/payments/" + payment.get("paymentId").asString(), Map.of("X-Api-Key", key))
                .body())
        .get("status")
        .asString();
  }

  private int callbackEvents() {
    return jdbc.sql(
            "SELECT count(*) FROM payment_attempt_events WHERE attempt_id = ?::uuid AND event = 'CALLBACK_SUCCESS'")
        .param(payment.get("paymentId").asString())
        .query(Integer.class)
        .single();
  }

  private static String newEventId() {
    return UUID.randomUUID().toString();
  }

  @Test
  void validCallbackCompletesPayment() throws Exception {
    HttpResponse<String> response = send(body(newEventId()));
    assertEquals(200, response.statusCode(), response.body());
    assertFalse(json.readTree(response.body()).get("duplicate").asBoolean());
    assertEquals("SUCCEEDED", paymentStatus());
  }

  @Test
  void rejectsWrongSecret() throws Exception {
    HttpResponse<String> response =
        send(URL, body(newEventId()), Instant.now().getEpochSecond(), "wrong-secret");
    assertEquals(401, response.statusCode());
    assertEquals("INVALID_SIGNATURE", json.readTree(response.body()).get("code").asString());
    assertEquals("PENDING", paymentStatus());
  }

  @Test
  void rejectsBodyChangedAfterSigning() throws Exception {
    long timestamp = Instant.now().getEpochSecond();
    String signed = body(newEventId());
    String changed = signed.replace("SUCCESS", "FAILED");
    HttpResponse<String> response =
        post(
            URL,
            Map.of(
                "X-Provider-Timestamp",
                String.valueOf(timestamp),
                "X-Provider-Signature",
                HmacSigner.sign(SECRET, timestamp, signed)),
            changed);
    assertEquals(401, response.statusCode());
    assertEquals("PENDING", paymentStatus());
  }

  @Test
  void rejectsOldTimestamp() throws Exception {
    long old = Instant.now().minusSeconds(3600).getEpochSecond();
    assertEquals(401, send(URL, body(newEventId()), old, SECRET).statusCode());
    assertEquals("PENDING", paymentStatus());
  }

  @Test
  void rejectsMissingSignature() throws Exception {
    assertEquals(400, post(URL, Map.of(), body(newEventId())).statusCode());
    assertEquals("PENDING", paymentStatus());
  }

  @Test
  void replayChangesNothing() throws Exception {
    String body = body(newEventId());
    send(body);
    HttpResponse<String> replay = send(body);
    assertEquals(200, replay.statusCode());
    assertTrue(json.readTree(replay.body()).get("duplicate").asBoolean());
    assertEquals(1, callbackEvents());
  }

  @Test
  void concurrentReplaysApplyOnce() throws Exception {
    String body = body(newEventId());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      for (int i = 0; i < 6; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return send(body);
            }));
      }
      start.countDown();
      for (Future<HttpResponse<String>> future : futures) {
        assertEquals(200, future.get().statusCode());
      }
    }
    assertEquals(1, callbackEvents());
  }

  @Test
  void rejectsCallbackThatDoesNotMatchPayment() throws Exception {
    String reference = payment.get("providerReference").asString();
    String merchantId = merchant.toString();
    List<String> mismatches =
        List.of(
            body(newEventId(), merchantId, reference, AMOUNT + 1, "IDR", "SUCCESS"),
            body(newEventId(), merchantId, reference, AMOUNT, "USD", "SUCCESS"),
            body(newEventId(), UUID.randomUUID().toString(), reference, AMOUNT, "IDR", "SUCCESS"),
            body(newEventId(), merchantId, "SBX-QR-OTHER", AMOUNT, "IDR", "SUCCESS"));
    for (String mismatch : mismatches) {
      HttpResponse<String> response = send(mismatch);
      assertEquals(422, response.statusCode(), mismatch);
      assertEquals("CALLBACK_MISMATCH", json.readTree(response.body()).get("code").asString());
    }
    assertEquals("PENDING", paymentStatus());
  }

  @Test
  void rejectedCallbackDoesNotUseItsEventId() throws Exception {
    String eventId = newEventId();
    send(
        body(
            eventId,
            merchant.toString(),
            payment.get("providerReference").asString(),
            AMOUNT + 1,
            "IDR",
            "SUCCESS"));
    assertEquals(200, send(body(eventId)).statusCode());
    assertEquals("SUCCEEDED", paymentStatus());
  }

  @Test
  void rejectsMalformedAndUnknownInput() throws Exception {
    assertEquals(400, send("{\"eventId\":\"x\"}").statusCode());
    assertEquals(400, send("not json").statusCode());
    long now = Instant.now().getEpochSecond();
    assertEquals(
        404,
        send("/api/v1/sandbox/provider-callbacks/other", body(newEventId()), now, SECRET)
            .statusCode());
  }

  @Test
  void staffCanReplayAnEventById() throws Exception {
    String path = "/api/v1/sandbox/payments/" + payment.get("paymentId").asString() + "/events";
    String body = "{\"outcome\":\"SUCCESS\",\"eventId\":\"" + newEventId() + "\"}";
    post(path, Map.of("X-Api-Key", operatorKey), body);
    post(path, Map.of("X-Api-Key", operatorKey), body);
    assertEquals(1, callbackEvents());
  }
}
