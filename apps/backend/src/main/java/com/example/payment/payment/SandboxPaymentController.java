package com.example.payment.payment;

import com.example.payment.merchant.ApiClient;
import com.example.payment.payment.PaymentStateMachine.Event;
import com.example.payment.provider.PaymentMethod;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: Lets staff simulate the provider's final answer for a payment.
 *
 * <p>Why: Phase 3 must complete a payment lifecycle before signed callbacks exist in Phase 4.
 *
 * <p>Safety: Exists only in the {@code demo} and {@code test} profiles and requires a staff role.
 * The event still passes through the state machine.
 *
 * <p>Tests: {@code PaymentApiIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/sandbox/payments")
@Profile({"demo", "test"})
public class SandboxPaymentController {

  private final PaymentEventService events;
  private final PaymentService payments;

  SandboxPaymentController(PaymentEventService events, PaymentService payments) {
    this.events = events;
    this.payments = payments;
  }

  /** The provider answers that can be simulated. */
  public enum Outcome {
    SUCCESS,
    FAILED
  }

  /**
   * A simulated provider answer.
   *
   * @param paidMethod optional, the method the provider reports for a success
   */
  public record SimulatedEvent(@NotNull Outcome outcome, PaymentMethod paidMethod) {}

  @Operation(summary = "SANDBOX: simulate the provider's answer for a payment")
  @PostMapping("/{paymentId}/events")
  public PaymentResponse simulate(
      @PathVariable UUID paymentId,
      @AuthenticationPrincipal ApiClient client,
      @Valid @RequestBody SimulatedEvent body) {
    PaymentResponse current = payments.getForClient(paymentId, client);
    Event event =
        body.outcome() == Outcome.SUCCESS ? Event.CALLBACK_SUCCESS : Event.CALLBACK_FAILED;
    events.apply(current.paymentId(), event, body.paidMethod(), "sandbox:" + client.id());
    return payments.getForClient(paymentId, client);
  }
}
