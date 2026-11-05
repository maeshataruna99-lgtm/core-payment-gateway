package com.example.payment.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Lets staff read the audit log and check its hash chain. Access is set in the security rules. */
@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController {

  private final AuditLog audit;

  AuditController(AuditLog audit) {
    this.audit = audit;
  }

  /** The result of checking the hash chain. */
  record Verification(boolean valid) {}

  @Operation(summary = "List audit events", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping
  List<AuditLog.Entry> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
    return audit.list(page, size);
  }

  @Operation(
      summary = "Check the audit hash chain",
      security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/verification")
  Verification verify() {
    return new Verification(audit.verify());
  }
}
