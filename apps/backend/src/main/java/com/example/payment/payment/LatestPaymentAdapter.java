package com.example.payment.payment;

import com.example.payment.checkout.LatestPaymentLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/** Tells the checkout module about a checkout's newest attempt. */
@Component
class LatestPaymentAdapter implements LatestPaymentLookup {

  private final PaymentAttemptRepository attempts;

  LatestPaymentAdapter(PaymentAttemptRepository attempts) {
    this.attempts = attempts;
  }

  @Override
  public Optional<Summary> latest(UUID checkoutId) {
    return attempts.findByCheckoutIdOrderByCreatedAtDesc(checkoutId, Limit.of(1)).stream()
        .findFirst()
        .map(a -> new Summary(a.id(), a.state().name(), a.method()));
  }
}
