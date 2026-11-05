package com.example.payment.payment;

import com.example.payment.merchant.ApiClient;
import com.example.payment.provider.PaymentMethod;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: Lets staff simulate the provider's final answer for a payment without signing it.
 *
 * <p>Why: A demo operator can trigger a payment, and replay an event, from the dashboard.
 *
 * <p>Safety: Exists only in the {@code demo} and {@code test} profiles and requires a staff role.
 * The answer takes the same checks and state machine as a signed callback.
 *
 * <p>Tests: {@code PaymentApiIntegrationTest}, {@code ProviderCallbackIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/sandbox/payments")
@Profile({"demo", "test"})
public class SandboxPaymentController {

  private final ProviderCallbackService callbacks;
  private final PaymentAttemptRepository attempts;
  private final PaymentService payments;

  SandboxPaymentController(
      ProviderCallbackService callbacks,
      PaymentAttemptRepository attempts,
      PaymentService payments) {
    this.callbacks = callbacks;
    this.attempts = attempts;
    this.payments = payments;
  }

  /**
   * A simulated provider answer.
   *
   * @param paidMethod optional, the method the provider reports for a success
   * @param eventId optional. Send one that was already used to replay that event.
   */
  public record SimulatedEvent(
      @NotNull ProviderCallback.Outcome outcome,
      PaymentMethod paidMethod,
      @Size(min = 1, max = 128) String eventId) {}

  @Operation(summary = "SANDBOX: simulate the provider's answer for a payment")
  @PostMapping("/{paymentId}/events")
  public PaymentResponse simulate(
      @PathVariable UUID paymentId,
      @AuthenticationPrincipal ApiClient client,
      @Valid @RequestBody SimulatedEvent body) {
    payments.getForClient(paymentId, client);
    PaymentAttempt attempt = attempts.findById(paymentId).orElseThrow();
    callbacks.process(
        "sandbox",
        new ProviderCallback(
            body.eventId() != null ? body.eventId() : UUID.randomUUID().toString(),
            attempt.id(),
            attempt.merchantId(),
            attempt.providerReference(),
            body.outcome(),
            attempt.amount(),
            attempt.currency(),
            body.paidMethod()));
    return payments.getForClient(paymentId, client);
  }
}
