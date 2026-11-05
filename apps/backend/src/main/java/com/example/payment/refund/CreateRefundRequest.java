package com.example.payment.refund;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * The body of a create-refund request.
 *
 * @param amount whole rupiah. An amount equal to the payment is a full refund.
 */
public record CreateRefundRequest(
    @Positive long amount, @NotBlank @Size(max = 255) String reason) {}
