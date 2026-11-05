package com.example.payment.checkout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * The body of a create-checkout request.
 *
 * @param amount whole rupiah
 * @param customerReference a synthetic ID. Its pattern rejects names, emails, and phone formats.
 * @param expiresAt optional, a default applies when missing
 * @param metadata optional, limited to allowlisted keys
 */
public record CreateCheckoutRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String merchantOrderId,
    @Positive long amount,
    @NotBlank String currency,
    @Size(max = 255) String description,
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String customerReference,
    Instant expiresAt,
    Map<String, String> metadata) {

  /** A stable text form of the request, for its idempotency hash. */
  String fingerprint() {
    return "checkout.create|"
        + new CreateCheckoutRequest(
            merchantOrderId,
            amount,
            currency,
            description,
            customerReference,
            expiresAt,
            metadata == null ? Map.of() : new TreeMap<>(metadata));
  }
}
