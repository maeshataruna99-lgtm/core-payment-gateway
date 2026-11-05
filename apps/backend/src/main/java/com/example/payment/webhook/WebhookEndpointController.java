package com.example.payment.webhook;

import com.example.payment.merchant.ApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: REST API for UC-06, a merchant's webhook endpoints.
 *
 * <p>How to use: Save the {@code secret} from the create response. It is never shown again. Verify
 * each webhook with it, as described in the project README.
 *
 * <p>Tests: {@code WebhookIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/webhook-endpoints")
class WebhookEndpointController {

  private final WebhookEndpointService endpoints;

  WebhookEndpointController(WebhookEndpointService endpoints) {
    this.endpoints = endpoints;
  }

  /** The body of a register request. */
  record RegisterRequest(@NotBlank String url, @NotEmpty List<String> events) {}

  @Operation(
      summary = "Register a webhook endpoint",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  WebhookEndpointService.Endpoint register(
      @AuthenticationPrincipal ApiClient client, @Valid @RequestBody RegisterRequest request) {
    return endpoints.register(client.merchantId(), client.actor(), request.url(), request.events());
  }

  @Operation(summary = "List webhook endpoints", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping
  List<WebhookEndpointService.Endpoint> list(@AuthenticationPrincipal ApiClient client) {
    return endpoints.list(client.merchantId());
  }

  @Operation(
      summary = "Disable a webhook endpoint",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping("/{endpointId}/disable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void disable(@AuthenticationPrincipal ApiClient client, @PathVariable UUID endpointId) {
    endpoints.disable(client.merchantId(), client.actor(), endpointId);
  }
}
