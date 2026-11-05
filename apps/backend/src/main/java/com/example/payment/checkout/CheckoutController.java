package com.example.payment.checkout;

import com.example.payment.merchant.ApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
 * What: REST API for UC-01 and UC-02, creating and reading a checkout session.
 *
 * <p>How to use: Merchants send {@code X-Api-Key}. A customer sends {@code X-Checkout-Token} with
 * the token from the checkout URL. A repeated create request returns the same checkout with the
 * {@code Idempotency-Replayed: true} header and its current status.
 *
 * <p>Safety: The API accepts no payment status from clients.
 *
 * <p>Tests: {@code CheckoutApiIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/checkout-sessions")
public class CheckoutController {

  private final CheckoutService checkouts;

  public CheckoutController(CheckoutService checkouts) {
    this.checkouts = checkouts;
  }

  @Operation(
      summary = "Create a simulated checkout session",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping
  public ResponseEntity<CheckoutResponse> create(
      @AuthenticationPrincipal ApiClient client,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
      @Valid @RequestBody CreateCheckoutRequest request) {
    CheckoutService.Created created = checkouts.create(client.merchantId(), key, request);
    return ResponseEntity.created(
            URI.create("/api/v1/checkout-sessions/" + created.checkout().checkoutId()))
        .header("Idempotency-Replayed", String.valueOf(created.replayed()))
        .body(created.checkout());
  }

  @Operation(summary = "Read a checkout session with an API key or a customer token")
  @GetMapping("/{checkoutId}")
  public CheckoutResponse get(
      @PathVariable UUID checkoutId,
      @AuthenticationPrincipal ApiClient client,
      @RequestHeader(value = "X-Checkout-Token", required = false) String token) {
    return client != null
        ? checkouts.getForClient(checkoutId, client)
        : checkouts.getForCustomer(checkoutId, token);
  }
}
