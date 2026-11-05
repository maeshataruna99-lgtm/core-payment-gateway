package com.example.payment.payment;

import com.example.payment.merchant.ApiClient;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: REST API for UC-03 and UC-04, starting and reading a simulated payment.
 *
 * <p>How to use: Merchants send {@code X-Api-Key}. A customer sends {@code X-Checkout-Token}. A
 * rejected or timed-out attempt still returns 201, because the attempt was created, so read its
 * {@code status}.
 *
 * <p>Safety: {@code PENDING} and {@code UNKNOWN} do not mean paid.
 *
 * <p>Tests: {@code PaymentApiIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @Operation(summary = "Start a simulated payment attempt")
  @PostMapping
  public ResponseEntity<PaymentResponse> create(
      @AuthenticationPrincipal ApiClient client,
      @RequestHeader(value = "X-Checkout-Token", required = false) String token,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
      @Valid @RequestBody CreatePaymentRequest request) {
    PaymentService.Created created =
        client != null
            ? payments.createForClient(client, key, request)
            : payments.createForCustomer(token, key, request);
    return ResponseEntity.created(URI.create("/api/v1/payments/" + created.payment().paymentId()))
        .header("Idempotency-Replayed", String.valueOf(created.replayed()))
        .body(created.payment());
  }

  @Operation(summary = "Read a simulated payment attempt")
  @GetMapping("/{paymentId}")
  public PaymentResponse get(
      @PathVariable UUID paymentId,
      @AuthenticationPrincipal ApiClient client,
      @RequestHeader(value = "X-Checkout-Token", required = false) String token) {
    return client != null
        ? payments.getForClient(paymentId, client)
        : payments.getForCustomer(paymentId, token);
  }
}
