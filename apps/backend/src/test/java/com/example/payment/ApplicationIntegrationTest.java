package com.example.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApplicationIntegrationTest extends AbstractIntegrationTest {

  @Autowired JdbcClient jdbc;

  @Test
  void healthIsPublicAndUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health", Map.of());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"status\":\"UP\""));
  }

  @Test
  void migrationsWereApplied() {
    int applied =
        jdbc.sql("select count(*) from flyway_schema_history where success")
            .query(Integer.class)
            .single();
    assertTrue(applied >= 2);
  }

  @Test
  void openApiContractIsLabelledAsSandbox() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs", Map.of());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("SANDBOX"));
  }

  @Test
  void unauthenticatedRequestUsesErrorEnvelope() throws Exception {
    HttpResponse<String> response = get("/api/v1/does-not-exist", Map.of());
    assertEquals(401, response.statusCode());
    assertTrue(response.body().contains("\"code\":\"UNAUTHORIZED\""));
    assertTrue(response.body().contains("\"traceId\""));
  }

  @Test
  void otherActuatorEndpointsAreClosed() throws Exception {
    assertEquals(401, get("/actuator/env", Map.of()).statusCode());
  }
}
