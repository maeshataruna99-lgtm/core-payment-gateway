package com.example.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.AbstractIntegrationTest;
import com.example.payment.common.Hashing;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
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

class PaymentApiIntegrationTest extends AbstractIntegrationTest {

  private static final String CHECKOUTS = "/api/v1/checkout-sessions";
  private static final String PAYMENTS = "/api/v1/payments";

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired PaymentSweeper sweeper;

  private String keyA;
  private String keyB;
  private String operatorKey;

  @BeforeEach
  void createClients() {
    keyA = createClient(createMerchant(), "MERCHANT");
    keyB = createClient(createMerchant(), "MERCHANT");
    operatorKey = createClient(null, "OPERATOR");
  }

  private UUID createMerchant() {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, 'Test')").param(id).update();
    return id;
  }

  private String createClient(UUID merchantId, String role) {
    String key = UUID.randomUUID().toString();
    jdbc.sql("INSERT INTO api_clients (id, merchant_id, role, key_hash) VALUES (?, ?, ?, ?)")
        .params(UUID.randomUUID(), merchantId, role, Hashing.sha256Hex(key))
        .update();
    return key;
  }

  private static String newKey() {
    return UUID.randomUUID().toString();
  }

  private JsonNode checkout(long amount) throws Exception {
    HttpResponse<String> response =
        post(
            CHECKOUTS,
            Map.of("X-Api-Key", keyA, "Idempotency-Key", newKey()),
            "{\"merchantOrderId\":\"O\",\"amount\":" + amount + ",\"currency\":\"IDR\"}");
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private static String qrisBody(JsonNode checkout) {
    return "{\"checkoutId\":\""
        + checkout.get("checkoutId").asString()
        + "\",\"paymentMethod\":\"QRIS\"}";
  }

  private HttpResponse<String> pay(String apiKey, String key, String body) throws Exception {
    return post(PAYMENTS, Map.of("X-Api-Key", apiKey, "Idempotency-Key", key), body);
  }

  private JsonNode payQris(long amount) throws Exception {
    return startQris(checkout(amount));
  }

  private JsonNode startQris(JsonNode checkout) throws Exception {
    HttpResponse<String> response = pay(keyA, newKey(), qrisBody(checkout));
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private HttpResponse<String> simulate(String apiKey, JsonNode payment, String outcome)
      throws Exception {
    return post(
        "/api/v1/sandbox/payments/" + payment.get("paymentId").asString() + "/events",
        Map.of("X-Api-Key", apiKey),
        "{\"outcome\":\"" + outcome + "\"}");
  }

  private JsonNode simulateAs(JsonNode payment, String outcome) throws Exception {
    HttpResponse<String> response = simulate(operatorKey, payment, outcome);
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private JsonNode read(JsonNode payment) throws Exception {
    return json.readTree(
        get(PAYMENTS + "/" + payment.get("paymentId").asString(), Map.of("X-Api-Key", keyA))
            .body());
  }

  private String checkoutStatus(JsonNode checkout) throws Exception {
    return json.readTree(
            get(CHECKOUTS + "/" + checkout.get("checkoutId").asString(), Map.of("X-Api-Key", keyA))
                .body())
        .get("status")
        .asString();
  }

  private static String status(JsonNode payment) {
    return payment.get("status").asString();
  }

  private int discrepancies(JsonNode payment, String type) {
    return jdbc.sql(
            "SELECT count(*) FROM payment_discrepancies WHERE attempt_id = ?::uuid AND type = ?")
        .params(payment.get("paymentId").asString(), type)
        .query(Integer.class)
        .single();
  }

  private void expireCheckoutAndAttempts(JsonNode checkout) {
    Timestamp past = Timestamp.from(Instant.now().minusSeconds(1));
    String id = checkout.get("checkoutId").asString();
    jdbc.sql("UPDATE checkout_sessions SET expires_at = ? WHERE id = ?::uuid")
        .params(past, id)
        .update();
    jdbc.sql("UPDATE payment_attempts SET expires_at = ? WHERE checkout_id = ?::uuid")
        .params(past, id)
        .update();
  }

  @Test
  void completesQrisLifecycle() throws Exception {
    JsonNode checkout = checkout(250000);
    JsonNode payment = startQris(checkout);
    assertEquals("PENDING", status(payment));
    assertTrue(payment.get("sandbox").asBoolean());
    assertTrue(
        payment.get("instruction").get("value").asString().startsWith("SANDBOX-NOT-PAYABLE"));
    assertEquals("OPEN", checkoutStatus(checkout));

    JsonNode paid = simulateAs(payment, "SUCCESS");
    assertEquals("SUCCEEDED", status(paid));
    assertEquals("QRIS", paid.get("paidMethod").asString());
    assertEquals("PAID", checkoutStatus(checkout));

    List<String> events = new ArrayList<>();
    paid.get("timeline").forEach(e -> events.add(e.get("event").asString()));
    assertEquals(List.of("CREATED", "PROVIDER_PENDING", "CALLBACK_SUCCESS"), events);
  }

  @Test
  void completesVirtualAccountLifecycle() throws Exception {
    JsonNode checkout = checkout(100000);
    HttpResponse<String> response =
        pay(
            keyA,
            newKey(),
            "{\"checkoutId\":\""
                + checkout.get("checkoutId").asString()
                + "\",\"paymentMethod\":\"VIRTUAL_ACCOUNT\",\"channelCode\":\"SIM_BANK_A\"}");
    assertEquals(201, response.statusCode(), response.body());
    JsonNode payment = json.readTree(response.body());
    assertEquals("PENDING", status(payment));
    assertEquals("SIM_BANK_A", payment.get("channelCode").asString());
    assertEquals("SUCCEEDED", status(simulateAs(payment, "SUCCESS")));
  }

  @Test
  void rejectsWrongChannel() throws Exception {
    JsonNode checkout = checkout(1000);
    String id = checkout.get("checkoutId").asString();
    assertEquals(
        400,
        pay(keyA, newKey(), "{\"checkoutId\":\"" + id + "\",\"paymentMethod\":\"VIRTUAL_ACCOUNT\"}")
            .statusCode());
    assertEquals(
        400,
        pay(
                keyA,
                newKey(),
                "{\"checkoutId\":\""
                    + id
                    + "\",\"paymentMethod\":\"QRIS\",\"channelCode\":\"SIM_BANK_A\"}")
            .statusCode());
    assertEquals(
        400,
        pay(keyA, newKey(), "{\"checkoutId\":\"" + id + "\",\"paymentMethod\":\"EWALLET\"}")
            .statusCode());
  }

  @Test
  void rejectsClientSuppliedStatus() throws Exception {
    String id = checkout(1000).get("checkoutId").asString();
    assertEquals(
        400,
        pay(
                keyA,
                newKey(),
                "{\"checkoutId\":\""
                    + id
                    + "\",\"paymentMethod\":\"QRIS\",\"status\":\"SUCCEEDED\"}")
            .statusCode());
  }

  @Test
  void providerRejectionFailsAttempt() throws Exception {
    assertEquals("FAILED", status(payQris(250001)));
  }

  @Test
  void timeoutIsUnknownUntilStatusCheckResolvesIt() throws Exception {
    JsonNode payment = payQris(250002);
    assertEquals("UNKNOWN", status(payment));
    sweeper.run();
    JsonNode resolved = read(payment);
    assertEquals("PENDING", status(resolved));
    assertNotNull(resolved.get("instruction"));
  }

  @Test
  void unknownAttemptSurvivesExpiry() throws Exception {
    JsonNode checkout = checkout(250003);
    JsonNode payment = startQris(checkout);
    assertEquals("UNKNOWN", status(payment));
    expireCheckoutAndAttempts(checkout);
    sweeper.run();
    assertEquals("UNKNOWN", status(read(payment)));
    assertEquals("SUCCEEDED", status(simulateAs(payment, "SUCCESS")));
    assertEquals("PAID", checkoutStatus(checkout));
  }

  @Test
  void pendingAttemptExpires() throws Exception {
    JsonNode checkout = checkout(1000);
    JsonNode payment = startQris(checkout);
    expireCheckoutAndAttempts(checkout);
    sweeper.run();
    assertEquals("EXPIRED", status(read(payment)));
    assertEquals("EXPIRED", checkoutStatus(checkout));
  }

  @Test
  void lateSuccessIsRecordedAndSettles() throws Exception {
    JsonNode checkout = checkout(1000);
    JsonNode payment = startQris(checkout);
    expireCheckoutAndAttempts(checkout);
    sweeper.run();
    assertEquals("SUCCEEDED", status(simulateAs(payment, "SUCCESS")));
    assertEquals("PAID", checkoutStatus(checkout));
    assertEquals(0, discrepancies(payment, "DOUBLE_PAYMENT"));
  }

  @Test
  void secondSuccessBecomesDiscrepancy() throws Exception {
    JsonNode checkout = checkout(1000);
    JsonNode first = startQris(checkout);
    JsonNode second = startQris(checkout);
    simulateAs(first, "SUCCESS");
    assertEquals("SUCCEEDED", status(simulateAs(second, "SUCCESS")));

    assertEquals(0, discrepancies(first, "DOUBLE_PAYMENT"));
    assertEquals(1, discrepancies(second, "DOUBLE_PAYMENT"));
    UUID paid =
        jdbc.sql("SELECT paid_attempt_id FROM checkout_sessions WHERE id = ?::uuid")
            .param(checkout.get("checkoutId").asString())
            .query(UUID.class)
            .single();
    assertEquals(first.get("paymentId").asString(), paid.toString());
  }

  @Test
  void concurrentSuccessesSettleOnce() throws Exception {
    JsonNode checkout = checkout(1000);
    List<JsonNode> payments = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      payments.add(startQris(checkout));
    }
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(payments.size())) {
      for (JsonNode payment : payments) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return simulate(operatorKey, payment, "SUCCESS");
            }));
      }
      start.countDown();
      for (Future<HttpResponse<String>> future : futures) {
        assertEquals(200, future.get().statusCode());
      }
    }
    int doubles = 0;
    for (JsonNode payment : payments) {
      doubles += discrepancies(payment, "DOUBLE_PAYMENT");
    }
    assertEquals(payments.size() - 1, doubles);
  }

  @Test
  void failureAfterSuccessChangesNothingAndIsRecorded() throws Exception {
    JsonNode payment = payQris(1000);
    simulateAs(payment, "SUCCESS");
    assertEquals("SUCCEEDED", status(simulateAs(payment, "FAILED")));
    assertEquals(1, discrepancies(payment, "CONTRADICTORY_EVENT"));
  }

  @Test
  void repeatedSuccessCreatesNoDiscrepancy() throws Exception {
    JsonNode payment = payQris(1000);
    simulateAs(payment, "SUCCESS");
    simulateAs(payment, "SUCCESS");
    assertEquals(0, discrepancies(payment, "DOUBLE_PAYMENT"));
    assertEquals(0, discrepancies(payment, "CONTRADICTORY_EVENT"));
  }

  @Test
  void paidOrExpiredCheckoutCannotStartPayment() throws Exception {
    JsonNode paid = checkout(1000);
    simulateAs(startQris(paid), "SUCCESS");
    HttpResponse<String> response = pay(keyA, newKey(), qrisBody(paid));
    assertEquals(409, response.statusCode());
    assertEquals("CHECKOUT_NOT_PAYABLE", json.readTree(response.body()).get("code").asString());

    JsonNode expired = checkout(1000);
    expireCheckoutAndAttempts(expired);
    assertEquals(409, pay(keyA, newKey(), qrisBody(expired)).statusCode());
  }

  @Test
  void replayDoesNotCreateSecondAttempt() throws Exception {
    JsonNode checkout = checkout(1000);
    String key = newKey();
    HttpResponse<String> first = pay(keyA, key, qrisBody(checkout));
    HttpResponse<String> second = pay(keyA, key, qrisBody(checkout));
    assertEquals(
        json.readTree(first.body()).get("paymentId"),
        json.readTree(second.body()).get("paymentId"));
    assertEquals("true", second.headers().firstValue("Idempotency-Replayed").orElseThrow());
    assertEquals(1, json.readTree(second.body()).get("attempts").size());
    assertEquals(409, pay(keyA, key, qrisBody(checkout(1000))).statusCode());
  }

  @Test
  void callerMustBeAuthorized() throws Exception {
    JsonNode checkout = checkout(1000);
    assertEquals(404, pay(keyB, newKey(), qrisBody(checkout)).statusCode());
    assertEquals(403, pay(operatorKey, newKey(), qrisBody(checkout)).statusCode());
    assertEquals(
        404, post(PAYMENTS, Map.of("Idempotency-Key", newKey()), qrisBody(checkout)).statusCode());

    JsonNode payment = startQris(checkout);
    String path = PAYMENTS + "/" + payment.get("paymentId").asString();
    assertEquals(404, get(path, Map.of("X-Api-Key", keyB)).statusCode());
    assertEquals(404, get(path, Map.of()).statusCode());
    assertEquals(200, get(path, Map.of("X-Api-Key", operatorKey)).statusCode());
  }

  @Test
  void customerCanPayWithTokenAndSeesLimitedView() throws Exception {
    JsonNode checkout = checkout(1000);
    String token = checkout.get("checkoutUrl").asString().split("#token=")[1];
    HttpResponse<String> response =
        post(
            PAYMENTS,
            Map.of("X-Checkout-Token", token, "Idempotency-Key", newKey()),
            qrisBody(checkout));
    assertEquals(201, response.statusCode(), response.body());
    JsonNode view =
        json.readTree(
            get(
                    PAYMENTS + "/" + json.readTree(response.body()).get("paymentId").asString(),
                    Map.of("X-Checkout-Token", token))
                .body());
    assertEquals("PENDING", status(view));
    assertNotNull(view.get("instruction"));
    assertFalse(view.has("timeline"));
    assertFalse(view.has("providerReference"));
  }

  @Test
  void customerKeysDoNotCollideAcrossCheckouts() throws Exception {
    JsonNode a = checkout(1000);
    JsonNode b = checkout(1000);
    String key = newKey();
    for (JsonNode checkout : List.of(a, b)) {
      String token = checkout.get("checkoutUrl").asString().split("#token=")[1];
      assertEquals(
          201,
          post(
                  PAYMENTS,
                  Map.of("X-Checkout-Token", token, "Idempotency-Key", key),
                  qrisBody(checkout))
              .statusCode());
    }
  }

  @Test
  void onlyStaffCanSimulate() throws Exception {
    JsonNode payment = payQris(1000);
    assertEquals(403, simulate(keyA, payment, "SUCCESS").statusCode());
    assertEquals("PENDING", status(read(payment)));
  }

  @Test
  void simulatedProviderCanReportDifferentMethod() throws Exception {
    JsonNode payment = payQris(1000);
    HttpResponse<String> response =
        post(
            "/api/v1/sandbox/payments/" + payment.get("paymentId").asString() + "/events",
            Map.of("X-Api-Key", operatorKey),
            "{\"outcome\":\"SUCCESS\",\"paidMethod\":\"VIRTUAL_ACCOUNT\"}");
    JsonNode paid = json.readTree(response.body());
    assertEquals("QRIS", paid.get("method").asString());
    assertEquals("VIRTUAL_ACCOUNT", paid.get("paidMethod").asString());
  }

  @Test
  void checkoutShowsLatestPayment() throws Exception {
    JsonNode checkout = checkout(1000);
    JsonNode payment = startQris(checkout);
    JsonNode view =
        json.readTree(
            get(CHECKOUTS + "/" + checkout.get("checkoutId").asString(), Map.of("X-Api-Key", keyA))
                .body());
    assertEquals(payment.get("paymentId"), view.get("latestPayment").get("paymentId"));
  }
}
