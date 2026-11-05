package com.example.payment.reconciliation;

import com.example.payment.merchant.ApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: REST API for UC-10, comparing a provider settlement file with internal payments.
 *
 * <p>How to use: Send the CSV file as the request body, with the merchant and period as query
 * parameters. Access is limited to staff in the security rules.
 *
 * <p>Tests: {@code ReconciliationApiIntegrationTest}.
 */
@RestController
@RequestMapping("/api/v1/reconciliation/runs")
class ReconciliationController {

  private final ReconciliationService reconciliation;

  ReconciliationController(ReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Operation(summary = "Run a reconciliation", security = @SecurityRequirement(name = "apiKey"))
  @PostMapping
  ResponseEntity<ReconciliationService.Run> run(
      @AuthenticationPrincipal ApiClient client,
      @RequestParam UUID merchantId,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestBody String settlementFile) {
    ReconciliationService.Run run =
        reconciliation.run(client, merchantId, from, to, settlementFile);
    return ResponseEntity.created(URI.create("/api/v1/reconciliation/runs/" + run.runId()))
        .body(run);
  }

  @Operation(
      summary = "Read a reconciliation run",
      security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/{runId}")
  ReconciliationService.Run get(@PathVariable UUID runId) {
    return reconciliation.get(runId);
  }

  @Operation(summary = "List reconciliation runs", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping
  List<ReconciliationService.Run> list(
      @RequestParam UUID merchantId,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
    return reconciliation.list(merchantId, page, size);
  }
}
