package com.example.payment.provider;

import org.springframework.stereotype.Component;

/** Simulated QRIS. Its payload is a sample that no banking app can pay. */
@Component
public class SandboxQrisProvider extends SandboxPaymentProvider {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.QRIS;
  }

  @Override
  public boolean supportsChannel(String channelCode) {
    return channelCode == null;
  }

  @Override
  String referencePrefix() {
    return "SBX-QR";
  }

  @Override
  Result pending(Request request, String reference) {
    return new Result(
        Result.Outcome.PENDING, reference, "Sample QR payload", "SANDBOX-NOT-PAYABLE-" + reference);
  }
}
