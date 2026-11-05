package com.example.payment.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuditLogIntegrationTest extends AbstractIntegrationTest {

  @Autowired AuditLog audit;
  @Autowired JdbcClient jdbc;

  @Test
  void chainIsValidAfterWrites() {
    audit.record("test:actor", "test.action", "test", UUID.randomUUID().toString());
    audit.record("test:actor", "test.action", "test", UUID.randomUUID().toString());
    assertTrue(audit.verify());
  }

  @Test
  void databaseRejectsChanges() {
    audit.record("test:actor", "test.action", "test", UUID.randomUUID().toString());
    assertThrows(
        DataAccessException.class,
        () -> jdbc.sql("UPDATE audit_events SET actor = 'someone-else'").update());
    assertThrows(DataAccessException.class, () -> jdbc.sql("DELETE FROM audit_events").update());
  }

  @Test
  void detectsEditThatBypassesTheTrigger() {
    String resource = UUID.randomUUID().toString();
    audit.record("test:actor", "test.action", "test", resource);
    audit.record("test:actor", "test.action", "test", UUID.randomUUID().toString());
    jdbc.sql("ALTER TABLE audit_events DISABLE TRIGGER audit_events_append_only").update();
    try {
      jdbc.sql("UPDATE audit_events SET actor = 'attacker' WHERE resource_id = ?")
          .param(resource)
          .update();
      assertFalse(audit.verify());
      jdbc.sql("UPDATE audit_events SET actor = 'test:actor' WHERE resource_id = ?")
          .param(resource)
          .update();
      assertTrue(audit.verify());
    } finally {
      jdbc.sql("ALTER TABLE audit_events ENABLE TRIGGER audit_events_append_only").update();
    }
  }

  @Test
  void webhookEndpointActionsAreAudited() throws Exception {
    String key = createClient(jdbc, createMerchant(jdbc), "MERCHANT");
    String id =
        post(
                "/api/v1/webhook-endpoints",
                Map.of("X-Api-Key", key),
                "{\"url\":\"http://localhost:1/x\",\"events\":[\"payment.failed\"]}")
            .body()
            .split("\"id\":\"")[1]
            .split("\"")[0];
    post("/api/v1/webhook-endpoints/" + id + "/disable", Map.of("X-Api-Key", key), "");
    long audited =
        jdbc.sql("SELECT count(*) FROM audit_events WHERE resource_id = ?")
            .param(id)
            .query(Long.class)
            .single();
    assertEquals(2, audited);
  }

  @Test
  void onlyStaffCanReadIt() throws Exception {
    String operatorKey = createClient(jdbc, null, "OPERATOR");
    String merchantKey = createClient(jdbc, createMerchant(jdbc), "MERCHANT");
    String path = "/api/v1/audit-events";
    assertEquals(200, get(path, Map.of("X-Api-Key", operatorKey)).statusCode());
    assertEquals(200, get(path + "/verification", Map.of("X-Api-Key", operatorKey)).statusCode());
    assertEquals(403, get(path, Map.of("X-Api-Key", merchantKey)).statusCode());
    assertEquals(401, get(path, Map.of()).statusCode());
  }
}
