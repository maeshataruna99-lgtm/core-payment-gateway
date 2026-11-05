package com.example.payment.settlement;

import com.example.payment.common.DomainException;
import com.example.payment.merchant.ApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
 * What: REST API for UC-09, previewing and finalizing a simulated settlement.
 *
 * <p>How to use: Staff preview a merchant, then finalize with {@code confirm: true} and the
 * preview's {@code net} as {@code expectedNet}. Merchants can read their own final batches.
 *
 * <p>Safety: Finalizing cannot be undone, so explicit confirmation is required. No money moves.
 *
 * <p>Tests: {@code SettlementApiIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/settlements")
class SettlementController {

  private final SettlementService settlements;

  SettlementController(SettlementService settlements) {
    this.settlements = settlements;
  }

  /** The body of a preview request. */
  record PreviewRequest(@NotNull UUID merchantId) {}

  /** The body of a finalize request. */
  record FinalizeRequest(@NotNull UUID merchantId, @Positive long expectedNet, boolean confirm) {}

  @Operation(
      summary = "Preview a simulated settlement",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping("/preview")
  SettlementService.Settlement preview(@Valid @RequestBody PreviewRequest request) {
    return settlements.preview(request.merchantId());
  }

  @Operation(
      summary = "Finalize a simulated settlement",
      security = @SecurityRequirement(name = "apiKey"))
  @PostMapping
  ResponseEntity<SettlementService.Settlement> finalizeBatch(
      @AuthenticationPrincipal ApiClient client,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String key,
      @Valid @RequestBody FinalizeRequest request) {
    if (!request.confirm()) {
      throw DomainException.invalidField("confirm", "Finalizing requires explicit confirmation.");
    }
    SettlementService.Finalized result =
        settlements.finalizeBatch(client, request.merchantId(), key, request.expectedNet());
    return ResponseEntity.created(URI.create("/api/v1/settlements/" + result.settlement().id()))
        .header("Idempotency-Replayed", String.valueOf(result.replayed()))
        .body(result.settlement());
  }

  @Operation(summary = "Read a final settlement", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/{settlementId}")
  SettlementService.Settlement get(
      @AuthenticationPrincipal ApiClient client, @PathVariable UUID settlementId) {
    return settlements.get(client, settlementId);
  }
}
