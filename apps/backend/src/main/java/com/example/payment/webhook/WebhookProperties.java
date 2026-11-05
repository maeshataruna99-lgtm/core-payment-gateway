package com.example.payment.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Webhook delivery settings.
 *
 * @param encryptionKey encrypts webhook secrets at rest
 * @param maxAutomaticAttempts attempts before a delivery becomes {@code FAILED}
 * @param maxTotalAttempts attempts including manual retries
 * @param allowedHosts hosts exempt from the SSRF rules. Only the local demo should set this.
 */
@ConfigurationProperties("payment.webhook")
@Validated
public record WebhookProperties(
    @NotBlank @Size(min = 32) String encryptionKey,
    @NotNull Duration baseDelay,
    @Positive int maxAutomaticAttempts,
    @Positive int maxTotalAttempts,
    @NotNull List<String> allowedHosts) {}
