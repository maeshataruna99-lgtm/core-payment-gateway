package com.example.payment.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.payment.payment.PaymentStateMachine.Event;
import com.example.payment.payment.PaymentStateMachine.State;
import com.example.payment.payment.PaymentStateMachine.Transition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Compares the code with the approved state table, so the two cannot drift apart. */
class PaymentStateMachineTest {

  private static final Path DECISIONS =
      Path.of("../../docs/architecture/decisions-payment-invariants.md");
  private static final List<Event> COLUMNS =
      List.of(
          Event.PROVIDER_PENDING,
          Event.PROVIDER_FAILED,
          Event.TIMEOUT,
          Event.CALLBACK_SUCCESS,
          Event.CALLBACK_FAILED,
          Event.EXPIRY);

  @Test
  void everyCellMatchesTheDecisionsDocument() throws Exception {
    List<String> rows =
        Files.readAllLines(DECISIONS).stream().filter(l -> l.startsWith("| `")).toList();
    assertEquals(State.values().length, rows.size(), "state rows in the document");

    for (String row : rows) {
      List<String> cells = Arrays.stream(row.split("\\|")).skip(1).map(String::trim).toList();
      State state = State.valueOf(cells.get(0).replace("`", ""));
      assertEquals(COLUMNS.size() + 1, cells.size(), row);
      for (int i = 0; i < COLUMNS.size(); i++) {
        Transition expected = parse(cells.get(i + 1), state);
        assertEquals(
            expected,
            PaymentStateMachine.next(state, COLUMNS.get(i)),
            state + " + " + COLUMNS.get(i));
      }
    }
  }

  private static Transition parse(String cell, State current) {
    if (cell.startsWith("No change")) {
      return new Transition(current, cell.contains("discrepancy"));
    }
    return new Transition(State.valueOf(cell.split("`")[1]), false);
  }
}
