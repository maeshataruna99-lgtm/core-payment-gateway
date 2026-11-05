package com.example.payment.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.AbstractIntegrationTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReconciliationApiIntegrationTest extends AbstractIntegrationTest {

  private static final Instant FROM = Instant.now().minus(1, ChronoUnit.DAYS);
  private static final Instant TO = Instant.now().plus(1, ChronoUnit.DAYS);
  private static final String RUNS = "/api/v1/reconciliation/runs";

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  private UUID merchant;
  private String key;
  private String operatorKey;

  @BeforeEach
  void createClients() {
    merchant = createMerchant(jdbc);
    key = createClient(jdbc, merchant, "MERCHANT");
    operatorKey = createClient(jdbc, null, "OPERATOR");
  }

  private String period() {
    return "merchantId=" + merchant + "&from=" + FROM + "&to=" + TO;
  }

  private String generatedFile(String faults) throws Exception {
    HttpResponse<String> response =
        get(
            "/api/v1/sandbox/settlement-files?" + period() + "&faults=" + faults,
            Map.of("X-Api-Key", operatorKey));
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

  private HttpResponse<String> send(String file) throws Exception {
    return post(RUNS + "?" + period(), Map.of("X-Api-Key", operatorKey), file);
  }

  private JsonNode run(String faults) throws Exception {
    HttpResponse<String> response = send(generatedFile(faults));
    assertEquals(201, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  private static List<String> types(JsonNode run) {
    List<String> types = new ArrayList<>();
    run.get("discrepancies").forEach(d -> types.add(d.get("type").asString()));
    return types.stream().sorted().toList();
  }

  private void pay(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      paidPayment(json, key, operatorKey, 100000);
    }
  }

  @Test
  void cleanFileHasNoDiscrepancies() throws Exception {
    pay(3);
    JsonNode run = run("");
    assertEquals(3, run.get("rowCount").asInt());
    assertEquals(3, run.get("matchedCount").asInt());
    assertEquals(0, run.get("discrepancyCount").asInt());
    assertTrue(run.get("sandbox").asBoolean());
  }

  @Test
  void detectsEveryKindOfDeliberateError() throws Exception {
    pay(5);
    JsonNode run = run("AMOUNT,STATUS,MISSING,DUPLICATE,UNKNOWN_REFERENCE");
    assertEquals(
        List.of(
            "AMOUNT_MISMATCH",
            "DUPLICATE",
            "MISSING_INTERNALLY",
            "MISSING_IN_PROVIDER",
            "STATUS_MISMATCH"),
        types(run));
    assertEquals(5, run.get("discrepancyCount").asInt());
    // The fifth payment has no fault, so it is the only match.
    assertEquals(1, run.get("matchedCount").asInt());
  }

  @Test
  void pendingPaymentThatProviderReportsPaidIsMismatch() throws Exception {
    JsonNode pending = startPayment(json, key, createCheckout(json, key, 1000));
    String file =
        SettlementFile.HEADER
            + "\n"
            + pending.get("providerReference").asString()
            + ",1000,IDR,SUCCESS,"
            + Instant.now()
            + "\n";
    HttpResponse<String> response = send(file);
    assertEquals(List.of("STATUS_MISMATCH"), types(json.readTree(response.body())));
  }

  @Test
  void neverChangesPaymentsOrLedger() throws Exception {
    pay(3);
    String before = snapshot();
    run("AMOUNT,STATUS,MISSING,UNKNOWN_REFERENCE");
    assertEquals(before, snapshot());
  }

  private String snapshot() {
    return jdbc.sql(
            """
            SELECT
              (SELECT string_agg(a.id || a.state || a.amount || a.updated_at, ',' ORDER BY a.id)
               FROM payment_attempts a WHERE a.merchant_id = ?)
              || '|' ||
              (SELECT count(*) FROM ledger_transactions t WHERE t.merchant_id = ?)
              || '|' ||
              (SELECT count(*) FROM payment_discrepancies d
               JOIN payment_attempts a ON a.id = d.attempt_id WHERE a.merchant_id = ?)
            """)
        .params(merchant, merchant, merchant)
        .query(String.class)
        .single();
  }

  @Test
  void otherMerchantsPaymentsAreNotUsed() throws Exception {
    String otherKey = createClient(jdbc, createMerchant(jdbc), "MERCHANT");
    JsonNode other = paidPayment(json, otherKey, operatorKey, 1000);
    String file =
        SettlementFile.HEADER
            + "\n"
            + other.get("providerReference").asString()
            + ",1000,IDR,SUCCESS,"
            + Instant.now()
            + "\n";
    assertEquals(List.of("MISSING_INTERNALLY"), types(json.readTree(send(file).body())));
  }

  @Test
  void paymentOutsidePeriodIsNotExpected() throws Exception {
    pay(1);
    Instant past = Instant.now().minus(30, ChronoUnit.DAYS);
    HttpResponse<String> response =
        post(
            RUNS + "?merchantId=" + merchant + "&from=" + past + "&to=" + past.plusSeconds(60),
            Map.of("X-Api-Key", operatorKey),
            SettlementFile.HEADER + "\n");
    assertEquals(0, json.readTree(response.body()).get("discrepancyCount").asInt());
  }

  @Test
  void storedRunCanBeReadAndListed() throws Exception {
    pay(1);
    JsonNode run = run("AMOUNT");
    String path = RUNS + "/" + run.get("runId").asString();
    JsonNode read = json.readTree(get(path, Map.of("X-Api-Key", operatorKey)).body());
    assertEquals(run.get("discrepancies"), read.get("discrepancies"));

    JsonNode list =
        json.readTree(
            get(RUNS + "?merchantId=" + merchant, Map.of("X-Api-Key", operatorKey)).body());
    assertEquals(1, list.size());
    assertEquals(1, list.get(0).get("discrepancyCount").asInt());
  }

  @Test
  void runsCannotBeChanged() throws Exception {
    pay(1);
    String id = run("AMOUNT").get("runId").asString();
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql("UPDATE reconciliation_runs SET matched_count = 99 WHERE id = ?::uuid")
                .param(id)
                .update());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql("DELETE FROM reconciliation_discrepancies WHERE run_id = ?::uuid")
                .param(id)
                .update());
  }

  @Test
  void runIsAudited() throws Exception {
    String id = run("").get("runId").asString();
    long audited =
        jdbc.sql(
                "SELECT count(*) FROM audit_events WHERE action = 'reconciliation.run' AND resource_id = ?")
            .param(id)
            .query(Long.class)
            .single();
    assertEquals(1, audited);
  }

  @Test
  void rejectsBadInput() throws Exception {
    assertEquals(400, send("not a settlement file").statusCode());
    assertEquals(
        400,
        post(
                RUNS + "?merchantId=" + merchant + "&from=" + TO + "&to=" + FROM,
                Map.of("X-Api-Key", operatorKey),
                SettlementFile.HEADER)
            .statusCode());
    assertEquals(
        404,
        post(
                RUNS + "?merchantId=" + UUID.randomUUID() + "&from=" + FROM + "&to=" + TO,
                Map.of("X-Api-Key", operatorKey),
                SettlementFile.HEADER)
            .statusCode());
    assertEquals(
        404, get(RUNS + "/" + UUID.randomUUID(), Map.of("X-Api-Key", operatorKey)).statusCode());
  }

  @Test
  void onlyStaffHaveAccess() throws Exception {
    String file = SettlementFile.HEADER;
    assertEquals(403, post(RUNS + "?" + period(), Map.of("X-Api-Key", key), file).statusCode());
    assertEquals(401, post(RUNS + "?" + period(), Map.of(), file).statusCode());
    assertEquals(
        403,
        get("/api/v1/sandbox/settlement-files?" + period(), Map.of("X-Api-Key", key)).statusCode());
  }
}
