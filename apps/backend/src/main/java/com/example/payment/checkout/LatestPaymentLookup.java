package com.example.payment.checkout;

import com.example.payment.provider.PaymentMethod;
import java.util.Optional;
import java.util.UUID;

/**
 * Lets a checkout show its newest payment attempt.
 *
 * <p>The payment module implements this interface, because the payment module depends on the
 * checkout module and the reverse would create a cycle.
 */
public interface LatestPaymentLookup {

  /** A short view of an attempt. */
  record Summary(UUID paymentId, String status, PaymentMethod method) {}

  Optional<Summary> latest(UUID checkoutId);
}
