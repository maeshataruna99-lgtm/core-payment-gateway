package com.example.payment.reconciliation;

import com.example.payment.payment.PaymentRecords;
import com.example.payment.payment.PaymentRecords.PaymentRecord;
import com.example.payment.reconciliation.SettlementFile.ProviderStatus;
import com.example.payment.reconciliation.SettlementFile.Row;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: Generates the file the simulated provider would send, with optional deliberate errors.
 *
 * <p>Why: A demo must show reconciliation finding real differences, with no real provider.
 *
 * <p>How to use: List errors in {@code faults}. Each one changes the next payment in time order, so
 * the result is repeatable: {@code AMOUNT}, {@code STATUS}, {@code MISSING}, {@code DUPLICATE}, and
 * {@code UNKNOWN_REFERENCE}, which adds a row for a payment that does not exist. A fault with no
 * payment left has no effect, except {@code UNKNOWN_REFERENCE}.
 *
 * <p>Safety: Exists only in the {@code demo} and {@code test} profiles. It only reads. Access is
 * limited to staff in the security rules.
 *
 * <p>Tests: {@code ReconciliationApiIntegrationTest}.
 */
@RestController
@Profile({"demo", "test"})
class SandboxSettlementFileController {

  /** A deliberate error to put in the file. */
  enum Fault {
    AMOUNT,
    STATUS,
    MISSING,
    DUPLICATE,
    UNKNOWN_REFERENCE
  }

  private final PaymentRecords payments;

  SandboxSettlementFileController(PaymentRecords payments) {
    this.payments = payments;
  }

  @Operation(
      summary = "SANDBOX: generate a provider settlement file",
      security = @SecurityRequirement(name = "apiKey"))
  @GetMapping(value = "/api/v1/sandbox/settlement-files", produces = "text/csv")
  String generate(
      @RequestParam UUID merchantId,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "") List<Fault> faults) {
    List<PaymentRecord> paid =
        payments.succeededBetween(merchantId, from, to).stream()
            .filter(p -> p.providerReference() != null)
            .sorted(Comparator.comparing(PaymentRecord::updatedAt))
            .toList();
    return SettlementFile.write(rows(paid, faults, to));
  }

  private static List<Row> rows(List<PaymentRecord> paid, List<Fault> faults, Instant to) {
    List<Fault> paymentFaults =
        faults.stream().filter(fault -> fault != Fault.UNKNOWN_REFERENCE).toList();
    List<Row> rows = new ArrayList<>();
    for (int i = 0; i < paid.size(); i++) {
      Row row = row(paid.get(i));
      Fault fault = i < paymentFaults.size() ? paymentFaults.get(i) : null;
      if (fault == Fault.MISSING) {
        continue;
      }
      if (fault == Fault.AMOUNT) {
        row =
            new Row(
                row.reference(),
                row.amount() + 1000,
                row.currency(),
                row.status(),
                row.occurredAt());
      }
      if (fault == Fault.STATUS) {
        row =
            new Row(
                row.reference(),
                row.amount(),
                row.currency(),
                ProviderStatus.FAILED,
                row.occurredAt());
      }
      rows.add(row);
      if (fault == Fault.DUPLICATE) {
        rows.add(row);
      }
    }
    if (faults.contains(Fault.UNKNOWN_REFERENCE)) {
      rows.add(
          new Row("SBX-UNKNOWN-0000", 1000, "IDR", ProviderStatus.SUCCESS, to.minusSeconds(1)));
    }
    return rows;
  }

  private static Row row(PaymentRecord payment) {
    return new Row(
        payment.providerReference(),
        payment.amount(),
        payment.currency(),
        ProviderStatus.SUCCESS,
        payment.updatedAt());
  }
}
