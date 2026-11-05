package com.example.payment.payment;

import com.example.payment.payment.PaymentStateMachine.State;
import com.example.payment.provider.PaymentMethod;
import com.example.payment.provider.PaymentProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One try to pay a checkout through one channel. */
@Entity
@Table(name = "payment_attempts")
class PaymentAttempt {

  @Id private UUID id;

  @Column(name = "checkout_id", nullable = false, updatable = false)
  private UUID checkoutId;

  @Column(name = "merchant_id", nullable = false, updatable = false)
  private UUID merchantId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private PaymentMethod method;

  @Column(name = "channel_code", updatable = false)
  private String channelCode;

  /** Whole rupiah. */
  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(nullable = false, updatable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private State state;

  @Column(name = "provider_reference")
  private String providerReference;

  @Column(name = "instruction_label")
  private String instructionLabel;

  @Column(name = "instruction_value")
  private String instructionValue;

  @Enumerated(EnumType.STRING)
  @Column(name = "paid_method")
  private PaymentMethod paidMethod;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PaymentAttempt() {}

  PaymentAttempt(
      UUID id,
      UUID checkoutId,
      UUID merchantId,
      PaymentMethod method,
      String channelCode,
      long amount,
      String currency,
      Instant expiresAt,
      Instant now) {
    this.id = id;
    this.checkoutId = checkoutId;
    this.merchantId = merchantId;
    this.method = method;
    this.channelCode = channelCode;
    this.amount = amount;
    this.currency = currency;
    this.state = State.CREATED;
    this.expiresAt = expiresAt;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Package-private so that only the payment module can move an attempt. */
  void moveTo(State next, Instant now) {
    this.state = next;
    this.updatedAt = now;
  }

  void recordProviderResult(PaymentProvider.Result result) {
    this.providerReference = result.reference();
    this.instructionLabel = result.instructionLabel();
    this.instructionValue = result.instructionValue();
  }

  void recordPaidMethod(PaymentMethod paid) {
    this.paidMethod = paid;
  }

  PaymentProvider.Request toProviderRequest() {
    return new PaymentProvider.Request(id, method, channelCode, amount);
  }

  UUID id() {
    return id;
  }

  UUID checkoutId() {
    return checkoutId;
  }

  UUID merchantId() {
    return merchantId;
  }

  PaymentMethod method() {
    return method;
  }

  String channelCode() {
    return channelCode;
  }

  long amount() {
    return amount;
  }

  String currency() {
    return currency;
  }

  State state() {
    return state;
  }

  String providerReference() {
    return providerReference;
  }

  String instructionLabel() {
    return instructionLabel;
  }

  String instructionValue() {
    return instructionValue;
  }

  PaymentMethod paidMethod() {
    return paidMethod;
  }

  Instant createdAt() {
    return createdAt;
  }

  Instant updatedAt() {
    return updatedAt;
  }
}
