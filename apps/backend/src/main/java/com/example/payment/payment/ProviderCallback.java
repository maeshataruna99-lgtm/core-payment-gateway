package com.example.payment.payment;

import com.example.payment.provider.PaymentMethod;
import java.util.UUID;

/**
 * A provider's final answer about a payment.
 *
 * @param eventId unique per event. A repeat is ignored.
 * @param amount whole rupiah
 * @param paidMethod optional, the method actually used
 */
public record ProviderCallback(
    String eventId,
    UUID paymentId,
    UUID merchantId,
    String providerReference,
    Outcome outcome,
    Long amount,
    String currency,
    PaymentMethod paidMethod) {

  /** What the provider reports. */
  public enum Outcome {
    SUCCESS,
    FAILED
  }
}
