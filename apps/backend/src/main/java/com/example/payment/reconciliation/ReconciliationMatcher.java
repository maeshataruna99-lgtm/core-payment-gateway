package com.example.payment.reconciliation;

import com.example.payment.payment.PaymentRecords.PaymentRecord;
import com.example.payment.reconciliation.SettlementFile.ProviderStatus;
import com.example.payment.reconciliation.SettlementFile.Row;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What: Compares a provider's settlement file with internal payments.
 *
 * <p>Why: Reconciliation is the check that catches what the other reliability measures miss. A pure
 * function can be tested for every kind of difference.
 *
 * <p>How: Matches on provider reference, then compares amount, currency, status, and date. A row
 * counts as matched only when it has no difference at all.
 *
 * <p>Tests: {@code ReconciliationMatcherTest}.
 */
final class ReconciliationMatcher {

  /** A provider's report can lag behind, so dates may differ by this much. */
  static final Duration DATE_TOLERANCE = Duration.ofHours(24);

  /** The kinds of difference. */
  enum Type {
    MISSING_IN_PROVIDER,
    MISSING_INTERNALLY,
    DUPLICATE,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    STATUS_MISMATCH,
    DATE_MISMATCH
  }

  /** One difference. Values are for a person to read. */
  record Finding(
      Type type, String reference, UUID paymentId, String internalValue, String providerValue) {}

  /**
   * The result of a comparison.
   *
   * @param matched references with no difference
   */
  record Result(int matched, List<Finding> findings) {}

  private ReconciliationMatcher() {}

  /**
   * Compares a file with internal payments.
   *
   * @param expected payments that succeeded in the period, which the file must contain
   * @param referenced payments whose references appear in the file
   */
  static Result match(
      List<Row> rows, Collection<PaymentRecord> expected, Collection<PaymentRecord> referenced) {
    Map<String, List<Row>> byReference =
        rows.stream()
            .collect(
                Collectors.groupingBy(Row::reference, LinkedHashMap::new, Collectors.toList()));
    Map<String, PaymentRecord> internal =
        referenced.stream()
            .filter(p -> p.providerReference() != null)
            .collect(
                Collectors.toMap(
                    PaymentRecord::providerReference, Function.identity(), (a, b) -> a));

    List<Finding> findings = new ArrayList<>();
    int matched = 0;
    for (Map.Entry<String, List<Row>> entry : byReference.entrySet()) {
      List<Finding> found = compare(entry.getKey(), entry.getValue(), internal.get(entry.getKey()));
      if (found.isEmpty()) {
        matched++;
      }
      findings.addAll(found);
    }
    for (PaymentRecord payment : expected) {
      if (payment.providerReference() == null
          || !byReference.containsKey(payment.providerReference())) {
        findings.add(
            new Finding(
                Type.MISSING_IN_PROVIDER,
                payment.providerReference(),
                payment.paymentId(),
                payment.state() + " " + payment.amount(),
                null));
      }
    }
    return new Result(matched, findings);
  }

  private static List<Finding> compare(String reference, List<Row> rows, PaymentRecord payment) {
    Row row = rows.get(0);
    UUID paymentId = payment == null ? null : payment.paymentId();
    List<Finding> found = new ArrayList<>();
    if (rows.size() > 1) {
      found.add(new Finding(Type.DUPLICATE, reference, paymentId, "1 row", rows.size() + " rows"));
    }
    if (payment == null) {
      found.add(
          new Finding(
              Type.MISSING_INTERNALLY, reference, null, null, row.status() + " " + row.amount()));
      return found;
    }
    if (payment.amount() != row.amount()) {
      found.add(
          new Finding(
              Type.AMOUNT_MISMATCH,
              reference,
              paymentId,
              Long.toString(payment.amount()),
              Long.toString(row.amount())));
    }
    if (!payment.currency().equals(row.currency())) {
      found.add(
          new Finding(
              Type.CURRENCY_MISMATCH, reference, paymentId, payment.currency(), row.currency()));
    }
    if (!statusAgrees(payment.state(), row.status())) {
      found.add(
          new Finding(
              Type.STATUS_MISMATCH, reference, paymentId, payment.state(), row.status().name()));
    }
    if (Duration.between(payment.updatedAt(), row.occurredAt()).abs().compareTo(DATE_TOLERANCE)
        > 0) {
      found.add(
          new Finding(
              Type.DATE_MISMATCH,
              reference,
              paymentId,
              payment.updatedAt().toString(),
              row.occurredAt().toString()));
    }
    return found;
  }

  private static boolean statusAgrees(String state, ProviderStatus status) {
    return status == ProviderStatus.SUCCESS
        ? "SUCCEEDED".equals(state)
        : "FAILED".equals(state) || "EXPIRED".equals(state);
  }
}
