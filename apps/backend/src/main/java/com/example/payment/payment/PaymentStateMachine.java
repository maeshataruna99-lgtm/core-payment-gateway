package com.example.payment.payment;

/**
 * What: The only definition of how a payment attempt's state may change.
 *
 * <p>Why: Payment status must never be updated arbitrarily. A pure function can be checked cell by
 * cell.
 *
 * <p>How: Implements the state table in {@code docs/architecture/decisions-payment-invariants.md}.
 *
 * <p>Tests: {@code PaymentStateMachineTest} compares this class with that document.
 */
public final class PaymentStateMachine {

  /** The state of a payment attempt. */
  public enum State {
    CREATED,
    PENDING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    EXPIRED
  }

  /** Something that happened to a payment attempt. */
  public enum Event {
    PROVIDER_PENDING,
    PROVIDER_FAILED,
    TIMEOUT,
    CALLBACK_SUCCESS,
    CALLBACK_FAILED,
    EXPIRY
  }

  /**
   * The result of an event.
   *
   * @param discrepancy true when the event contradicts a recorded success
   */
  public record Transition(State to, boolean discrepancy) {}

  private PaymentStateMachine() {}

  public static Transition next(State state, Event event) {
    return switch (state) {
      case CREATED ->
          move(
              switch (event) {
                case PROVIDER_PENDING -> State.PENDING;
                case PROVIDER_FAILED, CALLBACK_FAILED -> State.FAILED;
                case TIMEOUT -> State.UNKNOWN;
                case CALLBACK_SUCCESS -> State.SUCCEEDED;
                case EXPIRY -> State.EXPIRED;
              });
      case PENDING ->
          move(
              switch (event) {
                case PROVIDER_PENDING -> State.PENDING;
                case PROVIDER_FAILED, CALLBACK_FAILED -> State.FAILED;
                case TIMEOUT -> State.UNKNOWN;
                case CALLBACK_SUCCESS -> State.SUCCEEDED;
                case EXPIRY -> State.EXPIRED;
              });
      case UNKNOWN ->
          move(
              switch (event) {
                case PROVIDER_PENDING -> State.PENDING;
                case PROVIDER_FAILED, CALLBACK_FAILED -> State.FAILED;
                // Money may already have moved, so expiry does not end an unknown attempt.
                case TIMEOUT, EXPIRY -> State.UNKNOWN;
                case CALLBACK_SUCCESS -> State.SUCCEEDED;
              });
      case SUCCEEDED ->
          new Transition(
              State.SUCCEEDED, event == Event.PROVIDER_FAILED || event == Event.CALLBACK_FAILED);
      // A success that arrives late is still recorded, because the money has moved.
      case FAILED -> move(event == Event.CALLBACK_SUCCESS ? State.SUCCEEDED : State.FAILED);
      case EXPIRED -> move(event == Event.CALLBACK_SUCCESS ? State.SUCCEEDED : State.EXPIRED);
    };
  }

  private static Transition move(State to) {
    return new Transition(to, false);
  }
}
