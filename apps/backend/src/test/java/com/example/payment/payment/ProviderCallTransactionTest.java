package com.example.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.payment.AbstractIntegrationTest;
import com.example.payment.common.Hashing;
import com.example.payment.provider.SandboxQrisProvider;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Proves the architecture rule that provider calls run outside database transactions. */
class ProviderCallTransactionTest extends AbstractIntegrationTest {

  @MockitoSpyBean SandboxQrisProvider qris;
  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;

  @Test
  void providerRunsWithoutTransactionAndAfterAttemptIsSaved() throws Exception {
    UUID merchant = UUID.randomUUID();
    jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, 'M')").param(merchant).update();
    String key = UUID.randomUUID().toString();
    jdbc.sql(
            "INSERT INTO api_clients (id, merchant_id, role, key_hash) VALUES (?, ?, 'MERCHANT', ?)")
        .params(UUID.randomUUID(), merchant, Hashing.sha256Hex(key))
        .update();

    AtomicBoolean inTransaction = new AtomicBoolean(true);
    AtomicReference<Integer> savedBeforeCall = new AtomicReference<>();
    doAnswer(
            call -> {
              inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
              savedBeforeCall.set(
                  jdbc.sql("SELECT count(*) FROM payment_attempts WHERE merchant_id = ?")
                      .param(merchant)
                      .query(Integer.class)
                      .single());
              return call.callRealMethod();
            })
        .when(qris)
        .initiate(any());

    String checkout =
        post(
                "/api/v1/checkout-sessions",
                Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
                "{\"merchantOrderId\":\"O\",\"amount\":1000,\"currency\":\"IDR\"}")
            .body();
    String checkoutId = json.readTree(checkout).get("checkoutId").asString();
    assertEquals(
        201,
        post(
                "/api/v1/payments",
                Map.of("X-Api-Key", key, "Idempotency-Key", UUID.randomUUID().toString()),
                "{\"checkoutId\":\"" + checkoutId + "\",\"paymentMethod\":\"QRIS\"}")
            .statusCode());

    assertFalse(inTransaction.get());
    assertEquals(1, savedBeforeCall.get());
  }
}
