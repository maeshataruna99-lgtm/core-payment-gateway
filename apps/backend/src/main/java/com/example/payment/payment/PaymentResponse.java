package com.example.payment.payment;

import com.example.payment.payment.PaymentStateMachine.State;
import com.example.payment.provider.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A payment attempt as seen by an API caller. Fields left null are hidden from that caller.
 *
 * @param paymentId the ID of this attempt
 * @param amount whole rupiah
 * @param paidMethod the method the provider reports, which can differ from {@code method}
 * @param sandbox always true, because this system moves no real money
 * @param instruction sample payment details that cannot be paid
 * @param attempts all attempts on this checkout, newest first, at most 20
 * @param timeline the history of this attempt, hidden from customers
 * @param discrepancies recorded differences, hidden from customers
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
    UUID paymentId,
    UUID checkoutId,
    State status,
    PaymentMethod method,
    String channelCode,
    PaymentMethod paidMethod,
    long amount,
    String currency,
    boolean sandbox,
    String providerReference,
    Instruction instruction,
    Instant createdAt,
    Instant updatedAt,
    List<AttemptSummary> attempts,
    List<TimelineEntry> timeline,
    List<DiscrepancyEntry> discrepancies) {

  /** A recorded difference on this attempt, for review. */
  public record DiscrepancyEntry(String type, String detail, Instant at) {}

  /** What the customer is shown for a pending attempt. */
  public record Instruction(String label, String value) {}

  /** A short view of one attempt on the same checkout. */
  public record AttemptSummary(UUID paymentId, State status, PaymentMethod method) {}

  /** One recorded event. */
  public record TimelineEntry(Instant at, String event, String from, String to) {}
}
