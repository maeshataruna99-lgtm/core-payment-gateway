package com.example.payment.common;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What: Decides whether a money-related request is new or a repeat of an earlier one.
 *
 * <p>Why: A client that times out retries, and the retry must not create a second resource.
 *
 * <p>How: Inserts the key with {@code ON CONFLICT DO NOTHING}. A concurrent request with the same
 * key waits for the first transaction, then sees its committed row.
 *
 * <p>How to use: Call inside the transaction that creates the resource, so a failed creation also
 * removes the key. Include the operation name in the request hash, so one key cannot be reused
 * across operations.
 *
 * <p>Safety: The same key with a different request is rejected with 409.
 *
 * <p>Tests: {@code CheckoutApiIntegrationTest}.
 */
@Service
public class IdempotencyStore {

  private final JdbcClient jdbc;

  public IdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The outcome of claiming a key: the resource to use, and whether the request is a repeat. */
  public record Claim(UUID resourceId, boolean replayed) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public Claim claim(UUID merchantId, String key, String requestHash, UUID newResourceId) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO idempotency_keys
                  (merchant_id, idempotency_key, request_hash, resource_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                """)
            .params(merchantId, key, requestHash, newResourceId)
            .update();
    if (inserted == 1) {
      return new Claim(newResourceId, false);
    }
    Claim existing =
        jdbc.sql(
                """
                SELECT request_hash, resource_id FROM idempotency_keys
                WHERE merchant_id = ? AND idempotency_key = ?
                """)
            .params(merchantId, key)
            .query((rs, row) -> {
              if (!requestHash.equals(rs.getString("request_hash"))) {
                throw new DomainException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "This Idempotency-Key was already used with a different request.");
              }
              return new Claim(rs.getObject("resource_id", UUID.class), true);
            })
            .single();
    return existing;
  }
}
