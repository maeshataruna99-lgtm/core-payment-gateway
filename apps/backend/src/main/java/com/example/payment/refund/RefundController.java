package com.example.payment.refund;

import com.example.payment.merchant.ApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: REST API for UC-08, refunding a succeeded simulated payment.
 *
 * <p>How to use: A merchant refunds its own payments, and staff can refund any. Read {@code status}
 * in the response: a timed-out refund stays {@code PENDING}.
 *
 * <p>Tests: {@code RefundApiIntegrationTest}.
 */
@RestController
class RefundController {

  private final RefundService refunds;

  RefundController(RefundService refunds) {
    this.refunds = refunds;
  }

  @Operation(
      summary = "Refund a simulated payment",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping("/api/v1/payments/{paymentId}/refunds")
  ResponseEntity<RefundService.Refund> create(
      @AuthenticationPrincipal ApiClient client,
      @PathVariable UUID paymentId,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
      @Valid @RequestBody CreateRefundRequest request) {
    RefundService.Created created = refunds.create(client, paymentId, key, request);
    return ResponseEntity.status(201)
        .header("Idempotency-Replayed", String.valueOf(created.replayed()))
        .body(created.refund());
  }

  @Operation(summary = "List a payment's refunds", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/api/v1/payments/{paymentId}/refunds")
  RefundService.RefundList list(
      @AuthenticationPrincipal ApiClient client, @PathVariable UUID paymentId) {
    return refunds.list(client, paymentId);
  }
}
