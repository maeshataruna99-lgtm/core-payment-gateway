package com.example.payment.reconciliation;

import com.example.payment.common.DomainException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What: Reads and writes the provider settlement file, a simple CSV without quoting.
 *
 * <p>Safety: The file comes from outside, so every row is validated. A bad file is rejected whole,
 * because a partial comparison would report false differences.
 *
 * <p>Tests: {@code SettlementFileTest}.
 */
final class SettlementFile {

  static final String HEADER = "provider_reference,amount,currency,status,occurred_at";
  static final int MAX_ROWS = 10_000;
  static final int MAX_CHARS = 2_000_000;

  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  /** What a provider reports about a payment. */
  enum ProviderStatus {
    SUCCESS,
    FAILED
  }

  /** One row of the file. */
  record Row(
      String reference, long amount, String currency, ProviderStatus status, Instant occurredAt) {

    String toCsv() {
      return String.join(
          ",", reference, Long.toString(amount), currency, status.name(), occurredAt.toString());
    }
  }

  private SettlementFile() {}

  static List<Row> parse(String content) {
    if (content.length() > MAX_CHARS) {
      throw invalid("The file is too large.");
    }
    List<String> lines = content.lines().filter(line -> !line.isBlank()).toList();
    if (lines.isEmpty() || !HEADER.equals(lines.get(0).trim())) {
      throw invalid("The first line must be: " + HEADER);
    }
    if (lines.size() - 1 > MAX_ROWS) {
      throw invalid("The file has too many rows.");
    }
    List<Row> rows = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      rows.add(parseRow(lines.get(i), i + 1));
    }
    return rows;
  }

  static String write(List<Row> rows) {
    StringBuilder csv = new StringBuilder(HEADER).append('\n');
    rows.forEach(row -> csv.append(row.toCsv()).append('\n'));
    return csv.toString();
  }

  private static Row parseRow(String line, int number) {
    String[] fields = line.trim().split(",", -1);
    if (fields.length != 5
        || !REFERENCE.matcher(fields[0]).matches()
        || !CURRENCY.matcher(fields[2]).matches()) {
      throw invalid("Line " + number + " is not valid.");
    }
    try {
      long amount = Long.parseLong(fields[1]);
      if (amount <= 0) {
        throw invalid("Line " + number + " is not valid.");
      }
      return new Row(
          fields[0],
          amount,
          fields[2],
          ProviderStatus.valueOf(fields[3]),
          Instant.parse(fields[4]));
    } catch (IllegalArgumentException | DateTimeException e) {
      throw invalid("Line " + number + " is not valid.");
    }
  }

  private static DomainException invalid(String message) {
    return DomainException.invalidField("settlementFile", message);
  }
}
