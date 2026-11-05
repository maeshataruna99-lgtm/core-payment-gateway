package com.example.payment.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.provider.PaymentProvider.Request;
import com.example.payment.provider.PaymentProvider.Result;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SandboxPaymentProviderTest {

  private final SandboxQrisProvider qris = new SandboxQrisProvider();
  private final SandboxVirtualAccountProvider va = new SandboxVirtualAccountProvider();

  private static Request request(long amount) {
    return new Request(UUID.randomUUID(), PaymentMethod.QRIS, null, amount);
  }

  @Test
  void ordinaryAmountIsPendingWithSampleInstruction() {
    Result result = qris.initiate(request(250000));
    assertEquals(Result.Outcome.PENDING, result.outcome());
    assertTrue(result.instructionValue().startsWith("SANDBOX-NOT-PAYABLE-"));
  }

  @Test
  void amountEndingIn01IsRejected() {
    Result result = qris.initiate(request(250001));
    assertEquals(Result.Outcome.FAILED, result.outcome());
    assertNull(result.instructionValue());
  }

  @Test
  void amountEndingIn02TimesOutOnce() {
    Request request = request(250002);
    assertThrows(PaymentProvider.TimeoutException.class, () -> qris.initiate(request));
    assertEquals(Result.Outcome.PENDING, qris.checkStatus(request).outcome());
  }

  @Test
  void amountEndingIn03AlwaysTimesOut() {
    Request request = request(250003);
    assertThrows(PaymentProvider.TimeoutException.class, () -> qris.initiate(request));
    assertThrows(PaymentProvider.TimeoutException.class, () -> qris.checkStatus(request));
  }

  @Test
  void repeatedCallsReturnTheSameAnswer() {
    Request request = request(250000);
    assertEquals(va.initiate(request), va.checkStatus(request));
  }

  @Test
  void refundScenariosFollowTheAmount() {
    UUID id = UUID.randomUUID();
    PaymentProvider.RefundRequest ok = new PaymentProvider.RefundRequest(id, id, 1000);
    PaymentProvider.RefundRequest rejected = new PaymentProvider.RefundRequest(id, id, 1001);
    PaymentProvider.RefundRequest once = new PaymentProvider.RefundRequest(id, id, 1002);
    PaymentProvider.RefundRequest always = new PaymentProvider.RefundRequest(id, id, 1003);
    assertEquals(PaymentProvider.RefundOutcome.SUCCEEDED, qris.refund(ok));
    assertEquals(PaymentProvider.RefundOutcome.FAILED, qris.refund(rejected));
    assertThrows(PaymentProvider.TimeoutException.class, () -> qris.refund(once));
    assertEquals(PaymentProvider.RefundOutcome.SUCCEEDED, qris.checkRefund(once));
    assertThrows(PaymentProvider.TimeoutException.class, () -> qris.checkRefund(always));
  }

  @Test
  void channelRules() {
    assertTrue(qris.supportsChannel(null));
    assertFalse(qris.supportsChannel("SIM_BANK_A"));
    assertTrue(va.supportsChannel("SIM_BANK_A"));
    assertFalse(va.supportsChannel(null));
    assertFalse(va.supportsChannel("REAL_BANK"));
  }
}
