package com.example.payment.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.payment.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LedgerIntegrationTest extends AbstractIntegrationTest {

  @Autowired JdbcClient jdbc;
  @Autowired JsonMapper json;
  @Autowired LedgerService ledger;
  @Autowired PlatformTransactionManager transactionManager;

  private UUID merchant;
  private String key;
  private String operatorKey;

  @BeforeEach
  void createClients() {
    merchant = createMerchant(jdbc);
    key = createClient(jdbc, merchant, "MERCHANT");
    operatorKey = createClient(jdbc, null, "OPERATOR");
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
  void paymentSplitsIntoMerchantAndFee() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    assertEquals(99300, balance("merchant_payable"));
    assertEquals(700, balance("fee_revenue"));
    assertEquals(-100000, balance("provider_clearing"));
  }

  @Test
  void feeRoundsHalfUp() throws Exception {
    // 0.7% of 150 is 1.05, and of 1500 is 10.5.
    paidPayment(json, key, operatorKey, 150);
    assertEquals(1, balance("fee_revenue"));
    paidPayment(json, key, operatorKey, 1500);
    assertEquals(12, balance("fee_revenue"));
  }

  @Test
  void secondSuccessIsNotOwedToMerchant() throws Exception {
    JsonNode checkout = createCheckout(json, key, 100000);
    JsonNode first = startPayment(json, key, checkout);
    JsonNode second = startPayment(json, key, checkout);
    markSucceeded(first, operatorKey);
    markSucceeded(second, operatorKey);
    assertEquals(99300, balance("merchant_payable"));
    assertEquals(100000, balance("unapplied_funds"));
    assertEquals(-200000, balance("provider_clearing"));
  }

  @Test
  void failedOrPendingPaymentPostsNothing() throws Exception {
    startPayment(json, key, createCheckout(json, key, 100000));
    assertEquals(0, balance("provider_clearing"));
  }

  @Test
  void databaseRejectsUnbalancedTransaction() {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    assertThrows(
        DataAccessException.class,
        () ->
            tx.executeWithoutResult(status -> {
              UUID id = UUID.randomUUID();
              jdbc.sql(
                      """
                      INSERT INTO ledger_transactions (id, merchant_id, type, reference_id, created_at)
                      VALUES (?, ?, 'PAYMENT', ?, now())
                      """)
                  .params(id, merchant, UUID.randomUUID())
                  .update();
              jdbc.sql(
                      """
                      INSERT INTO ledger_entries (transaction_id, account, direction, amount)
                      VALUES (?, 'provider_clearing', 'DEBIT', 100)
                      """)
                  .param(id)
                  .update();
            }));
  }

  @Test
  void postedRowsCannotChange() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    for (String sql :
        List.of(
            "UPDATE ledger_entries SET amount = 1",
            "DELETE FROM ledger_entries",
            "UPDATE ledger_transactions SET type = 'REFUND'",
            "DELETE FROM ledger_transactions")) {
      assertThrows(DataAccessException.class, () -> jdbc.sql(sql).update(), sql);
    }
  }

  @Test
  void movementCannotBePostedTwice() {
    UUID payment = UUID.randomUUID();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.executeWithoutResult(status -> ledger.postPayment(merchant, payment, 1000, true));
    assertThrows(
        DataAccessException.class,
        () -> tx.executeWithoutResult(status -> ledger.postPayment(merchant, payment, 1000, true)));
  }

  @Test
  void everyTransactionInTheLedgerIsBalanced() throws Exception {
    paidPayment(json, key, operatorKey, 100000);
    long unbalanced =
        jdbc.sql(
                """
                SELECT count(*) FROM (
                  SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
                  HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                ) x
                """)
            .query(Long.class)
            .single();
    assertEquals(0, unbalanced);
  }
}
