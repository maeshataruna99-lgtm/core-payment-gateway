package com.example.payment.payment;

import com.example.payment.provider.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The body of a create-payment request.
 *
 * @param channelCode the simulated bank for a Virtual Account, null for QRIS
 */
public record CreatePaymentRequest(
    @NotNull UUID checkoutId, @NotNull PaymentMethod paymentMethod, String channelCode) {

  /** A stable text form of the request, for its idempotency hash. */
  String fingerprint() {
    return "payment.create|" + this;
  }
}
