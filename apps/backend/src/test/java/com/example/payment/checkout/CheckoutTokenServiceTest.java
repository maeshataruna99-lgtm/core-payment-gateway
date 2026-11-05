package com.example.payment.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutTokenServiceTest {

  private static CheckoutTokenService service(String secret) {
    return new CheckoutTokenService(
        new CheckoutProperties(secret, "http://x", 1, Duration.ofHours(1), Duration.ofDays(1)));
  }

  private final CheckoutTokenService tokens = service("first-secret-with-at-least-32-characters");

  @Test
  void acceptsOwnTokenAndIsStable() {
    UUID id = UUID.randomUUID();
    assertEquals(tokens.issue(id), tokens.issue(id));
    assertTrue(tokens.isValid(id, tokens.issue(id)));
  }

  @Test
  void rejectsMissingOrForeignToken() {
    UUID id = UUID.randomUUID();
    assertFalse(tokens.isValid(id, null));
    assertFalse(tokens.isValid(id, tokens.issue(UUID.randomUUID())));
    assertFalse(tokens.isValid(id, service("other-secret-with-at-least-32-characters").issue(id)));
  }
}
