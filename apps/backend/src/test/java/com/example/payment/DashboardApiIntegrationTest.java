package com.example.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DashboardApiIntegrationTest extends AbstractIntegrationTest {

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

  private JsonNode read(String path, String apiKey) throws Exception {
    HttpResponse<String> response = get(path, Map.of("X-Api-Key", apiKey));
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body());
  }

  @Test
  void meDescribesTheCaller() throws Exception {
    JsonNode me = read("/api/v1/me", key);
    assertEquals("MERCHANT", me.get("role").asString());
    assertEquals(merchant.toString(), me.get("merchantId").asString());
    assertEquals("OPERATOR", read("/api/v1/me", operatorKey).get("role").asString());
    assertEquals(401, get("/api/v1/me", Map.of("X-Api-Key", "wrong")).statusCode());
    assertTrue(read("/api/v1/me", key).toString().indexOf(key) < 0);
  }

  @Test
  void onlyStaffListMerchants() throws Exception {
    assertTrue(read("/api/v1/merchants", operatorKey).size() >= 1);
    assertEquals(403, get("/api/v1/merchants", Map.of("X-Api-Key", key)).statusCode());
  }

  @Test
  void listIsScopedAndFiltered() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 1000);
    startPayment(json, key, createCheckout(json, key, 2000));
    paidPayment(json, otherKey, operatorKey, 3000);

    assertEquals(2, read("/api/v1/payments", key).get("items").size());
    assertEquals(1, read("/api/v1/payments", otherKey).get("items").size());
    // A merchant cannot reach another merchant by naming it.
    assertEquals(1, read("/api/v1/payments?merchantId=" + merchant, otherKey).get("items").size());
    assertEquals(
        2, read("/api/v1/payments?merchantId=" + merchant, operatorKey).get("items").size());

    JsonNode succeeded =
        read("/api/v1/payments?status=SUCCEEDED&merchantId=" + merchant, operatorKey).get("items");
    assertEquals(1, succeeded.size());
    assertEquals(paid.get("paymentId"), succeeded.get(0).get("paymentId"));
    assertEquals(
        0,
        read("/api/v1/payments?method=VIRTUAL_ACCOUNT&merchantId=" + merchant, operatorKey)
            .get("items")
            .size());
    assertEquals(1, read("/api/v1/payments?size=1", key).get("items").size());
    assertEquals(401, get("/api/v1/payments", Map.of()).statusCode());
  }

  @Test
  void statsAreScoped() throws Exception {
    paidPayment(json, key, operatorKey, 1000);
    startPayment(json, key, createCheckout(json, key, 2000));
    paidPayment(json, otherKey, operatorKey, 3000);

    JsonNode stats = read("/api/v1/payment-stats", key);
    assertEquals(2, stats.get("total").asLong());
    assertEquals(1000, stats.get("succeededAmount").asLong());
    assertEquals(1, stats.get("counts").get("PENDING").asLong());
    assertEquals(0, stats.get("counts").get("FAILED").asLong());
    assertEquals(401, get("/api/v1/payment-stats", Map.of()).statusCode());
  }

  @Test
  void refundStatsCountOnlySuccess() throws Exception {
    JsonNode paid = paidPayment(json, key, operatorKey, 100000);
    String path = "/api/v1/payments/" + paid.get("paymentId").asString() + "/refunds";
    for (long amount : new long[] {5000, 5001}) {
      post(
          path,
          Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
          "{\"amount\":" + amount + ",\"reason\":\"x\"}");
    }
    JsonNode stats = read("/api/v1/refund-stats", key);
    assertEquals(1, stats.get("count").asLong());
    assertEquals(5000, stats.get("amount").asLong());
    assertEquals(0, read("/api/v1/refund-stats", otherKey).get("count").asLong());
  }

  @Test
  void settlementListIsScoped() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    post(
        "/api/v1/settlements",
        Map.of("X-Api-Key", operatorKey, "Idempotency-Key", UUID.randomUUID().toString()),
        "{\"merchantId\":\"%s\",\"expectedNet\":99300,\"confirm\":true}".formatted(merchant));
    assertEquals(1, read("/api/v1/settlements", key).size());
    assertEquals(0, read("/api/v1/settlements", otherKey).size());
    assertEquals(1, read("/api/v1/settlements?merchantId=" + merchant, operatorKey).size());
    assertEquals(99300, read("/api/v1/settlements", key).get(0).get("net").asLong());
  }

  @Test
  void paymentDetailShowsDiscrepancyToStaffOnly() throws Exception {
    JsonNode checkout = createCheckout(json, key, 1000);
    JsonNode first = startPayment(json, key, checkout);
    JsonNode second = startPayment(json, key, checkout);
    markSucceeded(first, operatorKey);
    markSucceeded(second, operatorKey);
    String path = "/api/v1/payments/" + second.get("paymentId").asString();
    assertEquals(
        "DOUBLE_PAYMENT", read(path, key).get("discrepancies").get(0).get("type").asString());
    String token = checkout.get("checkoutUrl").asString().split("#token=")[1];
    JsonNode customer = json.readTree(get(path, Map.of("X-Checkout-Token", token)).body());
    assertTrue(!customer.has("discrepancies"));
  }

  @Test
  void checkoutNamesItsMerchant() throws Exception {
    JsonNode checkout = createCheckout(json, key, 1000);
    assertEquals("Test", checkout.get("merchantName").asString());
    String token = checkout.get("checkoutUrl").asString().split("#token=")[1];
    JsonNode customer =
        json.readTree(
            get(
                    "/api/v1/checkout-sessions/" + checkout.get("checkoutId").asString(),
                    Map.of("X-Checkout-Token", token))
                .body());
    assertEquals("Test", customer.get("merchantName").asString());
  }
}
