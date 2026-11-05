package com.example.payment.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.example.payment.AbstractIntegrationTest;
import java.net.http.HttpResponse;
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

class RefundApiIntegrationTest extends AbstractIntegrationTest {

  private static final long PAID = 100000;

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired RefundService refunds;

  private UUID merchant;
  private String key;
  private String otherKey;
  private String operatorKey;
  private JsonNode payment;

  @BeforeEach
  void pay() throws Exception {
    merchant = createMerchant(jdbc);
    key = createClient(jdbc, merchant, "MERCHANT");
    otherKey = createClient(jdbc, createMerchant(jdbc), "MERCHANT");
    operatorKey = createClient(jdbc, null, "OPERATOR");
    payment = paidPayment(json, key, operatorKey, PAID);
  }

  private String path() {
    return "/api/v1/payments/" + payment.get("paymentId").asString() + "/refunds";
  }

  private HttpResponse<String> refund(String apiKey, String idempotencyKey, long amount)
      throws Exception {
    return post(
        path(),
        Map.of("X-Api-Key", apiKey, "Idempotency-Key", idempotencyKey),
        "{\"amount\":" + amount + ",\"reason\":\"Customer request\"}");
  }

  private JsonNode refund(long amount) throws Exception {
    HttpResponse<String> response = refund(key, UUID.randomUUID().toString(), amount);
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private long refundable() throws Exception {
    return json.readTree(get(path(), Map.of("X-Api-Key", key)).body())
        .get("refundableAmount")
        .asLong();
  }

  private long balance(String account) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(CASE e.direction WHEN 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id
            WHERE t.merchant_id = ? AND e.account = ?
            """)
        .params(merchant, account)
        .query(Long.class)
        .single();
  }

  @Test
  void partialRefundsUpToFullAmount() throws Exception {
    assertEquals("SUCCEEDED", refund(30000).get("status").asString());
    assertEquals(70000, refundable());
    assertEquals("SUCCEEDED", refund(70000).get("status").asString());
    assertEquals(0, refundable());

    HttpResponse<String> tooMuch = refund(key, UUID.randomUUID().toString(), 1000);
    assertEquals(409, tooMuch.statusCode());
    assertEquals("REFUND_EXCEEDS_BALANCE", json.readTree(tooMuch.body()).get("code").asString());
  }

  @Test
  void refundCannotExceedPayment() throws Exception {
    assertEquals(409, refund(key, UUID.randomUUID().toString(), PAID + 1).statusCode());
    assertEquals(PAID, refundable());
  }

  @Test
  void succeededRefundReducesWhatMerchantIsOwed() throws Exception {
    refund(30000);
    assertEquals(99300 - 30000, balance("merchant_payable"));
    assertEquals(-70000, balance("provider_clearing"));
    assertEquals(700, balance("fee_revenue"));
  }

  @Test
  void paymentStatusIsUnchanged() throws Exception {
    refund(PAID);
    JsonNode read =
        json.readTree(
            get("/api/v1/payments/" + payment.get("paymentId").asString(), Map.of("X-Api-Key", key))
                .body());
    assertEquals("SUCCEEDED", read.get("status").asString());
  }

  @Test
  void onlySucceededPaymentCanBeRefunded() throws Exception {
    payment = startPayment(json, key, createCheckout(json, key, PAID));
    HttpResponse<String> response = refund(key, UUID.randomUUID().toString(), 1000);
    assertEquals(409, response.statusCode());
    assertEquals("PAYMENT_NOT_REFUNDABLE", json.readTree(response.body()).get("code").asString());
  }

  @Test
  void failedRefundReleasesItsReservation() throws Exception {
    assertEquals("FAILED", refund(10001).get("status").asString());
    assertEquals(PAID, refundable());
    assertEquals(99300, balance("merchant_payable"));
  }

  @Test
  void timedOutRefundKeepsReservationUntilStatusCheck() throws Exception {
    JsonNode pending = refund(10002);
    assertEquals("PENDING", pending.get("status").asString());
    assertEquals(PAID - 10002, refundable());
    assertEquals(99300, balance("merchant_payable"));

    refunds.resolvePending();
    assertEquals("PENDING", status(pending));

    jdbc.sql("UPDATE refunds SET created_at = now() - interval '2 minutes' WHERE id = ?::uuid")
        .param(pending.get("refundId").asString())
        .update();
    refunds.resolvePending();
    assertEquals("SUCCEEDED", status(pending));
    assertEquals(99300 - 10002, balance("merchant_payable"));

    refunds.resolvePending();
    assertEquals(99300 - 10002, balance("merchant_payable"));
  }

  @Test
  void unknownRefundStaysPending() throws Exception {
    JsonNode pending = refund(10003);
    jdbc.sql("UPDATE refunds SET created_at = now() - interval '2 minutes' WHERE id = ?::uuid")
        .param(pending.get("refundId").asString())
        .update();
    refunds.resolvePending();
    assertEquals("PENDING", status(pending));
    assertEquals(PAID - 10003, refundable());
  }

  private String status(JsonNode refund) {
    return jdbc.sql("SELECT state FROM refunds WHERE id = ?::uuid")
        .param(refund.get("refundId").asString())
        .query(String.class)
        .single();
  }

  @Test
  void replayDoesNotRefundTwice() throws Exception {
    String idempotencyKey = UUID.randomUUID().toString();
    HttpResponse<String> first = refund(key, idempotencyKey, 30000);
    HttpResponse<String> second = refund(key, idempotencyKey, 30000);
    assertEquals(
        json.readTree(first.body()).get("refundId"), json.readTree(second.body()).get("refundId"));
    assertEquals("true", second.headers().firstValue("Idempotency-Replayed").orElseThrow());
    assertEquals(70000, refundable());
    assertEquals(409, refund(key, idempotencyKey, 30001).statusCode());
  }

  @Test
  void concurrentRefundsCannotExceedPayment() throws Exception {
    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return refund(key, UUID.randomUUID().toString(), 40000);
            }));
      }
      start.countDown();
      int created = 0;
      int rejected = 0;
      for (Future<HttpResponse<String>> future : futures) {
        int status = future.get().statusCode();
        if (status == 201) {
          created++;
        } else if (status == 409) {
          rejected++;
        }
      }
      assertEquals(2, created);
      assertEquals(threads - 2, rejected);
    }
    assertEquals(20000, refundable());
    assertEquals(99300 - 80000, balance("merchant_payable"));
  }

  @Test
  void secondPaymentRefundClearsUnappliedFunds() throws Exception {
    JsonNode checkout = createCheckout(json, key, PAID);
    JsonNode first = startPayment(json, key, checkout);
    JsonNode second = startPayment(json, key, checkout);
    markSucceeded(first, operatorKey);
    markSucceeded(second, operatorKey);
    payment = second;
    assertEquals(PAID, balance("unapplied_funds"));
    refund(PAID);
    assertEquals(0, balance("unapplied_funds"));
  }

  @Test
  void accessIsScoped() throws Exception {
    assertEquals(404, refund(otherKey, UUID.randomUUID().toString(), 1000).statusCode());
    assertEquals(404, get(path(), Map.of("X-Api-Key", otherKey)).statusCode());
    assertEquals(
        401,
        post(
                path(),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"amount\":1000,\"reason\":\"x\"}")
            .statusCode());
    assertEquals(201, refund(operatorKey, UUID.randomUUID().toString(), 1000).statusCode());
  }

  @Test
  void recordsWhoAskedInRefundAndAudit() throws Exception {
    JsonNode refund = refund(1000);
    String actor = refund.get("actor").asString();
    assertEquals(true, actor.startsWith("merchant:"));
    long audited =
        jdbc.sql(
                "SELECT count(*) FROM audit_events WHERE action = 'refund.created' AND resource_id = ?")
            .param(refund.get("refundId").asString())
            .query(Long.class)
            .single();
    assertEquals(1, audited);
  }

  @Test
  void rejectsInvalidRequests() throws Exception {
    for (String body :
        List.of(
            "{\"amount\":0,\"reason\":\"x\"}",
            "{\"amount\":-1,\"reason\":\"x\"}",
            "{\"amount\":1,\"reason\":\" \"}")) {
      assertEquals(
          400,
          post(
                  path(),
                  Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
                  body)
              .statusCode(),
          body);
    }
    assertFalse(
        refund(key, "bad", 1000).statusCode() == 201, "a malformed idempotency key was accepted");
  }
}
