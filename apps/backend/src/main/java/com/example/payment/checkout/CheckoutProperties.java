package com.example.payment.checkout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Checkout settings. Limits are simulated values, not regulatory ones. */
@ConfigurationProperties("payment.checkout")
@Validated
public record CheckoutProperties(
    @NotBlank @Size(min = 32) String tokenSecret,
    @NotBlank String baseUrl,
    @Positive long maxAmount,
    @NotNull Duration defaultTtl,
    @NotNull Duration maxTtl) {}
