package com.example.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the whole application against one real PostgreSQL shared by all tests. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort int port;

  protected HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path)));
    headers.forEach(request::header);
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  protected HttpResponse<String> post(String path, Map<String, String> headers, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(request::header);
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
