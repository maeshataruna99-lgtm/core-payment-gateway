package com.example.payment.payment;

import com.example.payment.checkout.CheckoutService;
import com.example.payment.payment.PaymentStateMachine.Event;
import com.example.payment.payment.PaymentStateMachine.State;
import com.example.payment.payment.PaymentStateMachine.Transition;
import com.example.payment.provider.PaymentMethod;
import com.example.payment.provider.PaymentProvider;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Applies an event to a payment attempt. It is the only code that changes attempt state.
 *
 * <p>Why: The state change, its history, and the checkout's settlement must commit together.
 *
 * <p>How: Locks the attempt row, asks {@link PaymentStateMachine} for the next state, and records
 * the event. A first success settles the checkout. A later success on that checkout, or an event
 * that contradicts a success, becomes a discrepancy.
 *
 * <p>How to use: Call with no provider call in progress. The row lock is held until commit, and it
 * must never be held across a network call.
 *
 * <p>Safety: Discrepancies are only recorded. Nothing here reverses a recorded success.
 *
 * <p>Tests: {@code PaymentApiIntegrationTest}.
 */
@Service
class PaymentEventService {

  private final PaymentAttemptRepository attempts;
  private final CheckoutService checkouts;
  private final JdbcClient jdbc;
  private final Clock clock;

  PaymentEventService(
      PaymentAttemptRepository attempts, CheckoutService checkouts, JdbcClient jdbc, Clock clock) {
    this.attempts = attempts;
    this.checkouts = checkouts;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public void applyProviderResult(UUID attemptId, PaymentProvider.Result result, String actor) {
    Event event =
        result.outcome() == PaymentProvider.Result.Outcome.PENDING
            ? Event.PROVIDER_PENDING
            : Event.PROVIDER_FAILED;
    PaymentAttempt attempt = attempts.findByIdForUpdate(attemptId).orElseThrow();
    boolean accepted = PaymentStateMachine.next(attempt.state(), event).to() != attempt.state();
    if (accepted) {
      attempt.recordProviderResult(result);
    }
    transition(attempt, event, null, actor);
  }

  /**
   * Applies an event.
   *
   * @param paidMethod the method the provider reports for a success, null to use the requested one
   */
  @Transactional
  public void apply(UUID attemptId, Event event, PaymentMethod paidMethod, String actor) {
    transition(attempts.findByIdForUpdate(attemptId).orElseThrow(), event, paidMethod, actor);
  }

  /** Records the creation of an attempt, inside the transaction that creates it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCreated(PaymentAttempt attempt, String actor) {
    recordEvent(attempt.id(), "CREATED", State.CREATED, State.CREATED, actor);
  }

  private void transition(
      PaymentAttempt attempt, Event event, PaymentMethod paidMethod, String actor) {
    State from = attempt.state();
    Transition transition = PaymentStateMachine.next(from, event);
    Instant now = clock.instant();
    if (transition.to() != from) {
      attempt.moveTo(transition.to(), now);
    }
    recordEvent(attempt.id(), event.name(), from, transition.to(), actor);
    if (transition.discrepancy()) {
      recordDiscrepancy(
          attempt.id(), "CONTRADICTORY_EVENT", event + " arrived after a recorded success");
    }
    if (transition.to() == State.SUCCEEDED && from != State.SUCCEEDED) {
      attempt.recordPaidMethod(paidMethod != null ? paidMethod : attempt.method());
      if (!checkouts.markPaid(attempt.checkoutId(), attempt.id())) {
        recordDiscrepancy(
            attempt.id(), "DOUBLE_PAYMENT", "The checkout was already paid by another attempt");
      }
    }
  }

  private void recordEvent(UUID attemptId, String event, State from, State to, String actor) {
    jdbc.sql(
            """
            INSERT INTO payment_attempt_events
              (attempt_id, event, from_state, to_state, actor, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """)
        .params(attemptId, event, from.name(), to.name(), actor, Timestamp.from(clock.instant()))
        .update();
  }

  private void recordDiscrepancy(UUID attemptId, String type, String detail) {
    jdbc.sql(
            """
            INSERT INTO payment_discrepancies (id, attempt_id, type, detail, created_at)
            VALUES (?, ?, ?, ?, ?)
            """)
        .params(UUID.randomUUID(), attemptId, type, detail, Timestamp.from(clock.instant()))
        .update();
  }
}
