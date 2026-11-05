package com.example.payment.reconciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.payment.common.DomainException;
import com.example.payment.reconciliation.SettlementFile.ProviderStatus;
import com.example.payment.reconciliation.SettlementFile.Row;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettlementFileTest {

  private static final String HEADER = SettlementFile.HEADER + "\n";

  @Test
  void writesWhatItReads() {
    List<Row> rows =
        List.of(
            new Row(
                "SBX-QR-1",
                250000,
                "IDR",
                ProviderStatus.SUCCESS,
                Instant.parse("2026-11-05T12:00:00Z")));
    assertEquals(rows, SettlementFile.parse(SettlementFile.write(rows)));
  }

  @Test
  void acceptsWindowsLineEndingsAndBlankLines() {
    String file = SettlementFile.HEADER + "\r\nA,1,IDR,FAILED,2026-11-05T12:00:00Z\r\n\r\n";
    assertEquals(1, SettlementFile.parse(file).size());
  }

  @Test
  void headerOnlyIsAnEmptyFile() {
    assertEquals(0, SettlementFile.parse(HEADER).size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "wrong,header\n",
        HEADER + "A,1,IDR,SUCCESS\n",
        HEADER + "A,abc,IDR,SUCCESS,2026-11-05T12:00:00Z\n",
        HEADER + "A,-1,IDR,SUCCESS,2026-11-05T12:00:00Z\n",
        HEADER + "A,0,IDR,SUCCESS,2026-11-05T12:00:00Z\n",
        HEADER + "A,1,idr,SUCCESS,2026-11-05T12:00:00Z\n",
        HEADER + "A,1,IDR,PAID,2026-11-05T12:00:00Z\n",
        HEADER + "A,1,IDR,SUCCESS,yesterday\n",
        HEADER + "a b,1,IDR,SUCCESS,2026-11-05T12:00:00Z\n",
        HEADER + "A,1,IDR,SUCCESS,2026-11-05T12:00:00Z,extra\n"
      })
  void rejectsInvalidFile(String file) {
    assertThrows(DomainException.class, () -> SettlementFile.parse(file));
  }

  @Test
  void errorNamesTheLine() {
    DomainException error =
        assertThrows(
            DomainException.class,
            () ->
                SettlementFile.parse(HEADER + "A,1,IDR,SUCCESS,2026-11-05T12:00:00Z\nbad line\n"));
    assertEquals("Line 3 is not valid.", error.getMessage());
  }

  @Test
  void rejectsTooManyRows() {
    String row = "A,1,IDR,SUCCESS,2026-11-05T12:00:00Z\n";
    String file = HEADER + row.repeat(SettlementFile.MAX_ROWS + 1);
    assertThrows(DomainException.class, () -> SettlementFile.parse(file));
  }
}
