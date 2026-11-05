package com.example.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Proves that simulator endpoints do not exist outside the sandbox profiles. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "payment.checkout.token-secret=test-only-secret-with-at-least-32-characters",
      "payment.webhook.encryption-key=test-only-encryption-key-with-32-characters",
      "payment.sweeper.cron=-",
      "payment.webhook.publish-cron=-",
      "payment.webhook.deliver-cron=-"
    })
@ActiveProfiles(value = "production-like", inheritProfiles = false)
class ProductionProfileIntegrationTest extends AbstractIntegrationTest {

  @Test
  void providerCallbackDoesNotExist() throws Exception {
    assertEquals(
        404,
        post(
                "/api/v1/sandbox/provider-callbacks/sandbox",
                Map.of("X-Provider-Timestamp", "1", "X-Provider-Signature", "x"),
                "{}")
            .statusCode());
  }

  @Test
  void demoWebhookReceiverDoesNotExist() throws Exception {
    assertEquals(
        404, post("/demo/webhook-receiver", Map.of("X-PayGate-Event-Id", "x"), "{}").statusCode());
  }

  @Test
  void simulatedAnswerEndpointIsClosed() throws Exception {
    assertEquals(
        401,
        post(
                "/api/v1/sandbox/payments/00000000-0000-4000-8000-000000000000/events",
                Map.of(),
                "{\"outcome\":\"SUCCESS\"}")
            .statusCode());
  }
}
