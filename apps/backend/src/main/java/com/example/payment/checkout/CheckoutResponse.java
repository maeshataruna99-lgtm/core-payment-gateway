package com.example.payment.checkout;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A checkout as seen by an API caller. Fields left null are hidden from that caller.
 *
 * @param amount whole rupiah
 * @param sandbox always true, because this system moves no real money
 * @param checkoutUrl hidden from customers. Its URL fragment holds the customer token, which
 *     browsers do not send to servers, so it stays out of access logs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutResponse(
    UUID checkoutId,
    String merchantOrderId,
    long amount,
    String currency,
    String description,
    CheckoutSession.Status status,
    Instant expiresAt,
    List<PaymentChannel> allowedChannels,
    boolean sandbox,
    String checkoutUrl,
    String customerReference,
    Map<String, String> metadata,
    Instant createdAt) {

  /** The simulated payment methods a customer can choose. */
  public enum PaymentChannel {
    QRIS,
    VIRTUAL_ACCOUNT,
    EWALLET,
    CARD_SIMULATED
  }
}
