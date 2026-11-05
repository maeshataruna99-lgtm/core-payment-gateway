package com.example.payment.provider;

import com.example.payment.common.Hashing;

/**
 * What: The simulated provider. Its behavior depends only on the amount, so demos and tests are
 * repeatable.
 *
 * <p>How to use: Choose a scenario with the last two digits of the amount:
 *
 * <ul>
 *   <li>{@code 01}: the provider rejects the payment.
 *   <li>{@code 02}: the first call times out. A later status check reports it as pending.
 *   <li>{@code 03}: every call times out.
 *   <li>Anything else: the payment is pending until a simulated callback arrives.
 * </ul>
 *
 * <p>Safety: It moves no money. Its instructions are sample values that cannot be paid.
 *
 * <p>Tests: {@code SandboxPaymentProviderTest}.
 */
abstract class SandboxPaymentProvider implements PaymentProvider {

  private static final int REJECT = 1;
  private static final int TIMEOUT_ONCE = 2;
  private static final int TIMEOUT_ALWAYS = 3;

  @Override
  public Result initiate(Request request) {
    int scenario = scenario(request);
    if (scenario == TIMEOUT_ONCE || scenario == TIMEOUT_ALWAYS) {
      throw new TimeoutException("Simulated provider timeout");
    }
    return answer(request);
  }

  @Override
  public Result checkStatus(Request request) {
    if (scenario(request) == TIMEOUT_ALWAYS) {
      throw new TimeoutException("Simulated provider timeout");
    }
    return answer(request);
  }

  /** Refund amounts choose the same scenarios, except that anything else succeeds at once. */
  @Override
  public RefundOutcome refund(RefundRequest request) {
    int scenario = (int) (request.amount() % 100);
    if (scenario == TIMEOUT_ONCE || scenario == TIMEOUT_ALWAYS) {
      throw new TimeoutException("Simulated provider timeout");
    }
    return scenario == REJECT ? RefundOutcome.FAILED : RefundOutcome.SUCCEEDED;
  }

  @Override
  public RefundOutcome checkRefund(RefundRequest request) {
    int scenario = (int) (request.amount() % 100);
    if (scenario == TIMEOUT_ALWAYS) {
      throw new TimeoutException("Simulated provider timeout");
    }
    return scenario == REJECT ? RefundOutcome.FAILED : RefundOutcome.SUCCEEDED;
  }

  /** The instruction shown to the customer for a pending attempt. */
  abstract Result pending(Request request, String reference);

  abstract String referencePrefix();

  /** Digits derived from the attempt, so repeated calls return the same sample value. */
  static String sampleDigits(Request request, int length) {
    return Hashing.sha256Hex(request.attemptId().toString())
        .chars()
        .mapToObj(c -> String.valueOf(c % 10))
        .limit(length)
        .reduce("", String::concat);
  }

  private Result answer(Request request) {
    String reference = referencePrefix() + "-" + sampleDigits(request, 10);
    if (scenario(request) == REJECT) {
      return new Result(Result.Outcome.FAILED, reference, null, null);
    }
    return pending(request, reference);
  }

  private static int scenario(Request request) {
    return (int) (request.amount() % 100);
  }
}
