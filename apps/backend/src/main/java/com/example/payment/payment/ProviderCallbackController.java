package com.example.payment.payment;

import com.example.payment.common.DomainException;
import com.example.payment.common.HmacSigner;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * What: REST API for UC-05, the simulated provider's signed callback.
 *
 * <p>How to use: Send {@code X-Provider-Timestamp} in epoch seconds and {@code
 * X-Provider-Signature} as the hex HMAC-SHA256 of {@code timestamp.body}. A repeated event ID
 * returns 200 with {@code duplicate: true}, so a provider stops retrying.
 *
 * <p>Safety: Exists only in the {@code demo} and {@code test} profiles. The signature covers the
 * raw request body, and an old timestamp is rejected.
 *
 * <p>Tests: {@code ProviderCallbackIntegrationTest}, {@code ProductionProfileIntegrationTest}.
 */
@RestController
@Profile({"demo", "test"})
public class ProviderCallbackController {

  private static final String SANDBOX_PROVIDER = "sandbox";
  private static final Duration MAX_AGE = Duration.ofMinutes(5);

  private final ProviderCallbackService callbacks;
  private final JsonMapper mapper;
  private final Clock clock;
  private final String secret;

  ProviderCallbackController(
      ProviderCallbackService callbacks,
      JsonMapper mapper,
      Clock clock,
      @Value("${payment.sandbox.callback-secret}") String secret) {
    this.callbacks = callbacks;
    this.mapper = mapper;
    this.clock = clock;
    this.secret = secret;
  }

  /** The answer to a callback. */
  public record CallbackResult(boolean duplicate) {}

  @Operation(summary = "SANDBOX: receive a signed callback from the simulated provider")
  @PostMapping("/api/v1/sandbox/provider-callbacks/{provider}")
  public CallbackResult receive(
      @PathVariable String provider,
      @RequestHeader("X-Provider-Timestamp") long timestamp,
      @RequestHeader("X-Provider-Signature") String signature,
      @RequestBody String body) {
    if (!SANDBOX_PROVIDER.equals(provider)) {
      throw new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown provider.");
    }
    long age = Math.abs(clock.instant().getEpochSecond() - timestamp);
    if (age > MAX_AGE.toSeconds() || !HmacSigner.matches(secret, timestamp, body, signature)) {
      throw new DomainException(
          HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "The callback signature is not valid.");
    }
    return new CallbackResult(!callbacks.process(provider, parse(body)));
  }

  private ProviderCallback parse(String body) {
    ProviderCallback callback;
    try {
      callback = mapper.readValue(body, ProviderCallback.class);
    } catch (JacksonException e) {
      throw new DomainException(
          HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The callback body is not valid.");
    }
    boolean complete =
        Stream.of(
                callback.eventId(),
                callback.paymentId(),
                callback.merchantId(),
                callback.outcome(),
                callback.amount(),
                callback.currency())
            .allMatch(java.util.Objects::nonNull);
    if (!complete || callback.eventId().isBlank() || callback.eventId().length() > 128) {
      throw new DomainException(
          HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The callback body is not valid.");
    }
    return callback;
  }
}
