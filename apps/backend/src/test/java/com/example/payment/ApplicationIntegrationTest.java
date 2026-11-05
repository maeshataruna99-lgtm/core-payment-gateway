package com.example.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts the whole application against a real PostgreSQL. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsUp() throws Exception {
    HttpResponse<String> response = get("/actuator/health");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"status\":\"UP\""));
  }

  @Test
  void baselineMigrationWasApplied() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '1' and success",
            Integer.class);
    assertEquals(1, applied);
  }

  @Test
  void openApiContractIsLabelledAsSandbox() throws Exception {
    HttpResponse<String> response = get("/v3/api-docs");
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("SANDBOX"));
  }

  @Test
  void unknownPathUsesErrorEnvelope() throws Exception {
    HttpResponse<String> response = get("/api/v1/does-not-exist");
    assertEquals(404, response.statusCode());
    assertTrue(response.body().contains("\"code\":\"NOT_FOUND\""));
    assertTrue(response.body().contains("\"traceId\""));
  }

  @Test
  void actuatorExposesOnlyHealth() throws Exception {
    assertEquals(404, get("/actuator/env").statusCode());
  }
}
