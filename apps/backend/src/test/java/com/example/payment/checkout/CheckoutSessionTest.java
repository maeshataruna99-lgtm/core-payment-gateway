package com.example.payment.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckoutSessionTest {

  private static final Instant EXPIRY = Instant.parse("2026-11-05T12:00:00Z");

  private final CheckoutSession session =
      new CheckoutSession(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "ORD-1",
          1,
          "IDR",
          null,
          null,
          Map.of(),
          EXPIRY,
          EXPIRY.minusSeconds(3600));

  @Test
  void isOpenBeforeExpiry() {
    assertEquals(CheckoutSession.Status.OPEN, session.status(EXPIRY.minusMillis(1)));
  }

  @Test
  void isExpiredAtExpiry() {
    assertEquals(CheckoutSession.Status.EXPIRED, session.status(EXPIRY));
  }
}
