package com.example.payment.provider;

import java.util.UUID;

/**
 * What: The port every payment provider adapter implements.
 *
 * <p>Why: Payment rules stay independent of any provider, and a real adapter could be added later
 * without changing them.
 *
 * <p>How to use: Call outside database transactions. Both operations must be safe to repeat for the
 * same attempt ID. A timeout or other failure means the outcome is unknown, not that it failed.
 *
 * <p>Safety: A provider never reports success here. Success only arrives as a callback.
 */
public interface PaymentProvider {

  /** A request to start or check one attempt. */
  record Request(UUID attemptId, PaymentMethod method, String channelCode, long amount) {}

  /** The provider's answer. Instruction fields are null when the attempt failed. */
  record Result(
      Outcome outcome, String reference, String instructionLabel, String instructionValue) {

    /** What the provider reported. */
    public enum Outcome {
      PENDING,
      FAILED
    }
  }

  /** The provider did not answer in time, so the outcome is unknown. */
  class TimeoutException extends RuntimeException {
    public TimeoutException(String message) {
      super(message);
    }
  }

  PaymentMethod method();

  boolean supportsChannel(String channelCode);

  Result initiate(Request request);

  Result checkStatus(Request request);
}
