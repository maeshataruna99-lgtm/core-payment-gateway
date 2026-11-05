package com.example.payment.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SettlementApiIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/settlements";

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  private UUID merchant;
  private String key;
  private String otherKey;
  private String operatorKey;

  @BeforeEach
  void createClients() {
    merchant = createMerchant(jdbc);
    key = createClient(jdbc, merchant, "MERCHANT");
    otherKey = createClient(jdbc, createMerchant(jdbc), "MERCHANT");
    operatorKey = createClient(jdbc, null, "OPERATOR");
  }

  private JsonNode preview() throws Exception {
    HttpResponse<String> response =
        post(
            PATH + "/preview",
            Map.of("X-Api-Key", operatorKey),
            "{\"merchantId\":\"" + merchant + "\"}");
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private HttpResponse<String> finalizeBatch(
      String idempotencyKey, long expectedNet, boolean confirm) throws Exception {
    return post(
        PATH,
        Map.of("X-Api-Key", operatorKey, "Idempotency-Key", idempotencyKey),
        "{\"merchantId\":\"%s\",\"expectedNet\":%d,\"confirm\":%s}"
            .formatted(merchant, expectedNet, confirm));
  }

  private JsonNode finalizeCurrent() throws Exception {
    HttpResponse<String> response =
        finalizeBatch(UUID.randomUUID().toString(), preview().get("net").asLong(), true);
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private void refund(JsonNode payment, long amount) throws Exception {
    HttpResponse<String> response =
        post(
            "/api/v1/payments/" + payment.get("paymentId").asString() + "/refunds",
            Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
            "{\"amount\":" + amount + ",\"reason\":\"x\"}");
    assertEquals(201, response.statusCode(), response.body());
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

  private static void assertTotals(
      JsonNode settlement, long gross, long fees, long refunds, long adjustments, long net) {
    assertEquals(gross, settlement.get("gross").asLong(), "gross");
    assertEquals(fees, settlement.get("fees").asLong(), "fees");
    assertEquals(refunds, settlement.get("refunds").asLong(), "refunds");
    assertEquals(adjustments, settlement.get("adjustments").asLong(), "adjustments");
    assertEquals(net, settlement.get("net").asLong(), "net");
  }

  @Test
  void previewCalculatesAndSavesNothing() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 100000);
    paidPayment(json, key, operatorKey, 50000);
    refund(paid, 20000);

    assertTotals(preview(), 150000, 1050, 20000, 0, 128950);
    assertTotals(preview(), 150000, 1050, 20000, 0, 128950);
    long batches =
        jdbc.sql("SELECT count(*) FROM settlement_batches WHERE merchant_id = ?")
            .param(merchant)
            .query(Long.class)
            .single();
    assertEquals(0, batches);
  }

  @Test
  void finalizingEmptiesWhatIsOwed() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 100000);
    refund(paid, 20000);
    JsonNode batch = finalizeCurrent();

    assertTotals(batch, 100000, 700, 20000, 0, 79300);
    assertEquals(0, balance("merchant_payable"));
    assertEquals(700, balance("fee_revenue"));
    assertEquals(-700, balance("provider_clearing"));
    assertTotals(preview(), 0, 0, 0, 0, 0);
  }

  @Test
  void requiresConfirmation() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    assertEquals(400, finalizeBatch(UUID.randomUUID().toString(), 99300, false).statusCode());
    assertTotals(preview(), 100000, 700, 0, 0, 99300);
  }

  @Test
  void rejectsAmountThatChangedSincePreview() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    long seen = preview().get("net").asLong();
    paidPayment(json, key, operatorKey, 1000);
    HttpResponse<String> response = finalizeBatch(UUID.randomUUID().toString(), seen, true);
    assertEquals(409, response.statusCode());
    assertEquals("SETTLEMENT_CHANGED", json.readTree(response.body()).get("code").asString());
  }

  @Test
  void nothingToSettle() throws Exception {
    HttpResponse<String> response = finalizeBatch(UUID.randomUUID().toString(), 1, true);
    assertEquals(409, response.statusCode());
    assertEquals("NOTHING_TO_SETTLE", json.readTree(response.body()).get("code").asString());
  }

  @Test
  void replayReturnsSameBatch() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    String idempotencyKey = UUID.randomUUID().toString();
    HttpResponse<String> first = finalizeBatch(idempotencyKey, 99300, true);
    HttpResponse<String> second = finalizeBatch(idempotencyKey, 99300, true);
    assertEquals(201, first.statusCode(), first.body());
    assertEquals(json.readTree(first.body()).get("id"), json.readTree(second.body()).get("id"));
    assertEquals("true", second.headers().firstValue("Idempotency-Replayed").orElseThrow());
  }

  @Test
  void refundAfterFinalBatchBecomesNextAdjustment() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 100000);
    JsonNode first = finalizeCurrent();
    refund(paid, 20000);
    paidPayment(json, key, operatorKey, 50000);

    JsonNode second = finalizeCurrent();
    assertTotals(second, 50000, 350, 0, -20000, 29650);
    assertTotals(
        json.readTree(
            get(PATH + "/" + first.get("id").asString(), Map.of("X-Api-Key", key)).body()),
        100000,
        700,
        0,
        0,
        99300);
  }

  @Test
  void adjustmentKeepsItsKindWhenRead() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 100000);
    finalizeCurrent();
    refund(paid, 20000);
    paidPayment(json, key, operatorKey, 50000);
    JsonNode batch = finalizeCurrent();
    JsonNode read =
        json.readTree(
            get(PATH + "/" + batch.get("id").asString(), Map.of("X-Api-Key", key)).body());
    List<String> kinds = new ArrayList<>();
    read.get("lines").forEach(l -> kinds.add(l.get("kind").asString()));
    assertEquals(List.of("ADJUSTMENT", "PAYMENT"), kinds.stream().sorted().toList());
  }

  @Test
  void netOfZeroOrLessWaitsForNextBatch() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 100000);
    finalizeCurrent();
    refund(paid, 50000);
    assertEquals(-50000, preview().get("net").asLong());
    HttpResponse<String> response = finalizeBatch(UUID.randomUUID().toString(), 1, true);
    assertEquals("NOTHING_TO_SETTLE", json.readTree(response.body()).get("code").asString());

    paidPayment(json, key, operatorKey, 100000);
    assertTotals(finalizeCurrent(), 100000, 700, 0, -50000, 49300);
  }

  @Test
  void secondSuccessIsNeverSettled() throws Exception {
    JsonNode checkout = createCheckout(json, key, 100000);
    JsonNode first = startPayment(json, key, checkout);
    JsonNode second = startPayment(json, key, checkout);
    markSucceeded(first, operatorKey);
    markSucceeded(second, operatorKey);
    assertTotals(preview(), 100000, 700, 0, 0, 99300);
  }

  @Test
  void concurrentFinalizingCreatesOneBatch() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<HttpResponse<String>>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
      for (int i = 0; i < 5; i++) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return finalizeBatch(UUID.randomUUID().toString(), 99300, true);
            }));
      }
      start.countDown();
      int created = 0;
      for (Future<HttpResponse<String>> future : futures) {
        if (future.get().statusCode() == 201) {
          created++;
        }
      }
      assertEquals(1, created);
    }
    assertEquals(0, balance("merchant_payable"));
  }

  @Test
  void finalBatchCannotChange() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    String id = finalizeCurrent().get("id").asString();
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql("UPDATE settlement_batches SET net = 1 WHERE id = ?::uuid")
                .param(id)
                .update());
    assertThrows(
        DataAccessException.class,
        () -> jdbc.sql("DELETE FROM settlement_batches WHERE id = ?::uuid").param(id).update());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql("DELETE FROM ledger_settlement_marks WHERE batch_id = ?::uuid")
                .param(id)
                .update());
  }

  @Test
  void accessIsScoped() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    String previewBody = "{\"merchantId\":\"" + merchant + "\"}";
    assertEquals(403, post(PATH + "/preview", Map.of("X-Api-Key", key), previewBody).statusCode());
    assertEquals(
        403,
        post(
                PATH,
                Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
                "{\"merchantId\":\"" + merchant + "\",\"expectedNet\":99300,\"confirm\":true}")
            .statusCode());
    assertEquals(
        404,
        post(
                PATH + "/preview",
                Map.of("X-Api-Key", operatorKey),
                "{\"merchantId\":\"" + UUID.randomUUID() + "\"}")
            .statusCode());

    String batch = PATH + "/" + finalizeCurrent().get("id").asString();
    assertEquals(200, get(batch, Map.of("X-Api-Key", key)).statusCode());
    assertEquals(404, get(batch, Map.of("X-Api-Key", otherKey)).statusCode());
    assertEquals(401, get(batch, Map.of()).statusCode());
  }

  @Test
  void finalizingIsAudited() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    String id = finalizeCurrent().get("id").asString();
    long audited =
        jdbc.sql(
                "SELECT count(*) FROM audit_events WHERE action = 'settlement.finalized' AND resource_id = ?")
            .param(id)
            .query(Long.class)
            .single();
    assertEquals(1, audited);
  }
}
