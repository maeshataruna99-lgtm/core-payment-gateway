package com.example.payment.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.payment.payment.PaymentRecords.PaymentRecord;
import com.example.payment.reconciliation.ReconciliationMatcher.Finding;
import com.example.payment.reconciliation.ReconciliationMatcher.Result;
import com.example.payment.reconciliation.ReconciliationMatcher.Type;
import com.example.payment.reconciliation.SettlementFile.ProviderStatus;
import com.example.payment.reconciliation.SettlementFile.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationMatcherTest {

  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

  private static PaymentRecord payment(String reference, String state) {
    return new PaymentRecord(UUID.randomUUID(), reference, 1000, "IDR", state, NOW);
  }

  private static Row row(String reference) {
    return new Row(reference, 1000, "IDR", ProviderStatus.SUCCESS, NOW);
  }

  private static List<Type> types(Result result) {
    return result.findings().stream().map(Finding::type).toList();
  }

  private static Result matchOne(Row row, PaymentRecord payment) {
    return ReconciliationMatcher.match(List.of(row), List.of(payment), List.of(payment));
  }

  @Test
  void identicalRecordsMatch() {
    Result result = matchOne(row("A"), payment("A", "SUCCEEDED"));
    assertEquals(1, result.matched());
    assertTrue(result.findings().isEmpty());
  }

  @Test
  void findsPaymentMissingFromFile() {
    PaymentRecord payment = payment("A", "SUCCEEDED");
    Result result = ReconciliationMatcher.match(List.of(), List.of(payment), List.of());
    assertEquals(List.of(Type.MISSING_IN_PROVIDER), types(result));
    assertEquals(payment.paymentId(), result.findings().get(0).paymentId());
  }

  @Test
  void succeededPaymentWithoutReferenceIsMissing() {
    PaymentRecord payment = payment(null, "SUCCEEDED");
    Result result = ReconciliationMatcher.match(List.of(), List.of(payment), List.of());
    assertEquals(List.of(Type.MISSING_IN_PROVIDER), types(result));
  }

  @Test
  void findsRowWithNoInternalPayment() {
    Result result = ReconciliationMatcher.match(List.of(row("X")), List.of(), List.of());
    assertEquals(List.of(Type.MISSING_INTERNALLY), types(result));
    assertEquals(0, result.matched());
  }

  @Test
  void findsDuplicateRows() {
    PaymentRecord payment = payment("A", "SUCCEEDED");
    Result result =
        ReconciliationMatcher.match(
            List.of(row("A"), row("A"), row("A")), List.of(payment), List.of(payment));
    assertEquals(List.of(Type.DUPLICATE), types(result));
    assertEquals("3 rows", result.findings().get(0).providerValue());
  }

  @Test
  void findsAmountMismatch() {
    Result result =
        matchOne(new Row("A", 999, "IDR", ProviderStatus.SUCCESS, NOW), payment("A", "SUCCEEDED"));
    assertEquals(List.of(Type.AMOUNT_MISMATCH), types(result));
    assertEquals("1000", result.findings().get(0).internalValue());
    assertEquals("999", result.findings().get(0).providerValue());
    assertEquals(0, result.matched());
  }

  @Test
  void findsCurrencyMismatch() {
    Result result =
        matchOne(new Row("A", 1000, "USD", ProviderStatus.SUCCESS, NOW), payment("A", "SUCCEEDED"));
    assertEquals(List.of(Type.CURRENCY_MISMATCH), types(result));
  }

  @Test
  void findsStatusMismatchInBothDirections() {
    assertEquals(
        List.of(Type.STATUS_MISMATCH),
        types(
            matchOne(
                new Row("A", 1000, "IDR", ProviderStatus.FAILED, NOW), payment("A", "SUCCEEDED"))));
    PaymentRecord unknown = payment("B", "UNKNOWN");
    assertEquals(
        List.of(Type.STATUS_MISMATCH),
        types(ReconciliationMatcher.match(List.of(row("B")), List.of(), List.of(unknown))));
  }

  @Test
  void failedAgreesWithFailedAndExpired() {
    Row failed = new Row("A", 1000, "IDR", ProviderStatus.FAILED, NOW);
    assertEquals(
        1,
        ReconciliationMatcher.match(List.of(failed), List.of(), List.of(payment("A", "FAILED")))
            .matched());
    assertEquals(
        1,
        ReconciliationMatcher.match(List.of(failed), List.of(), List.of(payment("A", "EXPIRED")))
            .matched());
  }

  @Test
  void dateMayDifferOnlyWithinTolerance() {
    PaymentRecord payment = payment("A", "SUCCEEDED");
    Instant edge = NOW.plus(ReconciliationMatcher.DATE_TOLERANCE);
    assertEquals(
        1, matchOne(new Row("A", 1000, "IDR", ProviderStatus.SUCCESS, edge), payment).matched());
    Result late =
        matchOne(
            new Row("A", 1000, "IDR", ProviderStatus.SUCCESS, edge.plus(Duration.ofSeconds(1))),
            payment);
    assertEquals(List.of(Type.DATE_MISMATCH), types(late));
  }

  @Test
  void oneRowCanHaveSeveralDifferences() {
    Result result =
        matchOne(new Row("A", 999, "USD", ProviderStatus.FAILED, NOW), payment("A", "SUCCEEDED"));
    assertEquals(
        List.of(Type.AMOUNT_MISMATCH, Type.CURRENCY_MISMATCH, Type.STATUS_MISMATCH), types(result));
  }
}
