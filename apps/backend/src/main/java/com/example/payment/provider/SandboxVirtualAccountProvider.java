package com.example.payment.provider;

import java.util.Set;
import org.springframework.stereotype.Component;

/** Simulated Virtual Account. Its banks and numbers do not exist. */
@Component
public class SandboxVirtualAccountProvider extends SandboxPaymentProvider {

  private static final Set<String> BANKS = Set.of("SIM_BANK_A", "SIM_BANK_B", "SIM_BANK_C");

  @Override
  public PaymentMethod method() {
    return PaymentMethod.VIRTUAL_ACCOUNT;
  }

  @Override
  public boolean supportsChannel(String channelCode) {
    return channelCode != null && BANKS.contains(channelCode);
  }

  @Override
  String referencePrefix() {
    return "SBX-VA";
  }

  @Override
  Result pending(Request request, String reference) {
    return new Result(
        Result.Outcome.PENDING, reference, "Sample VA number", "0000" + sampleDigits(request, 12));
  }
}
