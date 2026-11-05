package com.example.payment.payment;

import com.example.payment.payment.PaymentStateMachine.State;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives other modules a read-only view of payments, without exposing the entity.
 *
 * <p>Tests: {@code ReconciliationApiIntegrationTest}.
 */
@Component
public class PaymentRecords {

  /** A payment as reconciliation sees it. {@code updatedAt} is when its state last changed. */
  public record PaymentRecord(
      UUID paymentId,
      String providerReference,
      long amount,
      String currency,
      String state,
      Instant updatedAt) {}

  private final PaymentAttemptRepository attempts;

  PaymentRecords(PaymentAttemptRepository attempts) {
    this.attempts = attempts;
  }

  /** Returns payments that succeeded in {@code [from, to)}. */
  @Transactional(readOnly = true)
  public List<PaymentRecord> succeededBetween(UUID merchantId, Instant from, Instant to) {
    return attempts
        .findByMerchantIdAndStateAndUpdatedAtGreaterThanEqualAndUpdatedAtLessThan(
            merchantId, State.SUCCEEDED, from, to)
        .stream()
        .map(PaymentRecords::record)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<PaymentRecord> withReferences(UUID merchantId, Collection<String> references) {
    if (references.isEmpty()) {
      return List.of();
    }
    return attempts.findByMerchantIdAndProviderReferenceIn(merchantId, references).stream()
        .map(PaymentRecords::record)
        .toList();
  }

  private static PaymentRecord record(PaymentAttempt attempt) {
    return new PaymentRecord(
        attempt.id(),
        attempt.providerReference(),
        attempt.amount(),
        attempt.currency(),
        attempt.state().name(),
        attempt.updatedAt());
  }
}
