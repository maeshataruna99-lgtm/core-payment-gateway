package com.example.payment.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A merchant's request to collect an amount for one order.
 *
 * <p>Its status is derived from the clock, so an expired session is never reported as open, even if
 * no background job has run.
 */
@Entity
@Table(name = "checkout_sessions")
public class CheckoutSession {

  /** The lifecycle of a session. Phase 3 adds the paid state. */
  public enum Status {
    OPEN,
    EXPIRED
  }

  @Id private UUID id;

  @Column(name = "merchant_id", nullable = false, updatable = false)
  private UUID merchantId;

  @Column(name = "merchant_order_id", nullable = false, updatable = false)
  private String merchantOrderId;

  /** Whole rupiah. */
  @Column(nullable = false, updatable = false)
  private long amount;

  @Column(nullable = false, updatable = false)
  private String currency;

  @Column(updatable = false)
  private String description;

  @Column(name = "customer_reference", updatable = false)
  private String customerReference;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
  private Map<String, String> metadata;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CheckoutSession() {}

  public CheckoutSession(
      UUID id,
      UUID merchantId,
      String merchantOrderId,
      long amount,
      String currency,
      String description,
      String customerReference,
      Map<String, String> metadata,
      Instant expiresAt,
      Instant createdAt) {
    this.id = id;
    this.merchantId = merchantId;
    this.merchantOrderId = merchantOrderId;
    this.amount = amount;
    this.currency = currency;
    this.description = description;
    this.customerReference = customerReference;
    this.metadata = metadata;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  public Status status(Instant now) {
    return now.isBefore(expiresAt) ? Status.OPEN : Status.EXPIRED;
  }

  public UUID id() {
    return id;
  }

  public UUID merchantId() {
    return merchantId;
  }

  public String merchantOrderId() {
    return merchantOrderId;
  }

  public long amount() {
    return amount;
  }

  public String currency() {
    return currency;
  }

  public String description() {
    return description;
  }

  public String customerReference() {
    return customerReference;
  }

  public Map<String, String> metadata() {
    return metadata;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
