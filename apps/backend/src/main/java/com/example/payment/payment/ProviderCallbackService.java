package com.example.payment.payment;

import com.example.payment.common.DomainException;
import com.example.payment.payment.PaymentStateMachine.Event;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Applies a provider callback to a payment attempt, at most once per event ID.
 *
 * <p>Why: A provider delivers callbacks at least once, and an arbitrary body must not be able to
 * mark a payment as paid.
 *
 * <p>How: Records the event ID with {@code ON CONFLICT DO NOTHING}, checks the callback against the
 * attempt, then applies it through the state machine, all in one transaction.
 *
 * <p>How to use: Authenticate the callback first. A rejected callback rolls back, so its event ID
 * stays unused.
 *
 * <p>Safety: Merchant, amount, currency, and reference must all match the attempt.
 *
 * <p>Tests: {@code ProviderCallbackIntegrationTest}.
 */
@Service
class ProviderCallbackService {

  private final PaymentAttemptRepository attempts;
  private final PaymentEventService events;
  private final JdbcClient jdbc;
  private final Clock clock;

  ProviderCallbackService(
      PaymentAttemptRepository attempts, PaymentEventService events, JdbcClient jdbc, Clock clock) {
    this.attempts = attempts;
    this.events = events;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Processes a callback.
   *
   * @return false when this event ID was already processed, so nothing changed
   */
  @Transactional
  public boolean process(String provider, ProviderCallback callback) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO provider_callback_events (provider, event_id, received_at)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """)
            .params(provider, callback.eventId(), Timestamp.from(clock.instant()))
            .update();
    if (inserted == 0) {
      return false;
    }
    PaymentAttempt attempt =
        attempts
            .findById(callback.paymentId())
            .orElseThrow(
                () -> new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Payment not found."));
    verifyMatches(callback, attempt);
    Event event =
        callback.outcome() == ProviderCallback.Outcome.SUCCESS
            ? Event.CALLBACK_SUCCESS
            : Event.CALLBACK_FAILED;
    events.apply(attempt.id(), event, callback.paidMethod(), "provider:" + provider);
    return true;
  }

  private static void verifyMatches(ProviderCallback callback, PaymentAttempt attempt) {
    boolean referenceMatches =
        attempt.providerReference() == null
            || attempt.providerReference().equals(callback.providerReference());
    if (!attempt.merchantId().equals(callback.merchantId())
        || attempt.amount() != callback.amount()
        || !attempt.currency().equals(callback.currency())
        || !referenceMatches) {
      throw new DomainException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "CALLBACK_MISMATCH",
          "The callback does not match the payment.");
    }
  }
}
