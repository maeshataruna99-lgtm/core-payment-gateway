package com.example.payment.payment;

import com.example.payment.merchant.ApiClient;
import com.example.payment.payment.PaymentStateMachine.State;
import com.example.payment.provider.PaymentMethod;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: Lists payments and their totals for the transaction monitor and overview.
 *
 * <p>How to use: Staff see every merchant unless they pass {@code merchantId}. A merchant only ever
 * sees its own payments, whatever it passes.
 *
 * <p>Tests: {@code DashboardApiIntegrationTest}.
 */
@RestController
class PaymentListController {

  private final JdbcClient jdbc;

  PaymentListController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** One row of the monitor. */
  record PaymentRow(
      UUID paymentId,
      UUID checkoutId,
      UUID merchantId,
      State status,
      PaymentMethod method,
      long amount,
      String currency,
      Instant createdAt,
      Instant updatedAt) {}

  /** One page of payments, newest first. */
  record Page(List<PaymentRow> items, int page, int size) {}

  /**
   * Payment totals.
   *
   * @param counts attempts per state
   * @param succeededAmount the simulated volume, whole rupiah
   */
  record Stats(Map<String, Long> counts, long succeededAmount, long total) {}

  @Operation(summary = "List payments", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/api/v1/payments")
  Page list(
      @AuthenticationPrincipal ApiClient client,
      @RequestParam(required = false) UUID merchantId,
      @RequestParam(required = false) State status,
      @RequestParam(required = false) PaymentMethod method,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
    UUID scope = scope(client, merchantId);
    String statusName = status == null ? null : status.name();
    String methodName = method == null ? null : method.name();
    Timestamp fromTime = from == null ? null : Timestamp.from(from);
    Timestamp toTime = to == null ? null : Timestamp.from(to);
    List<PaymentRow> items =
        jdbc.sql(
                """
                SELECT id, checkout_id, merchant_id, state, method, amount, currency,
                       created_at, updated_at
                FROM payment_attempts
                WHERE (?::uuid IS NULL OR merchant_id = ?)
                  AND (?::text IS NULL OR state = ?)
                  AND (?::text IS NULL OR method = ?)
                  AND (?::timestamptz IS NULL OR created_at >= ?)
                  AND (?::timestamptz IS NULL OR created_at < ?)
                ORDER BY created_at DESC, id LIMIT ? OFFSET ?
                """)
            .params(
                scope,
                scope,
                statusName,
                statusName,
                methodName,
                methodName,
                fromTime,
                fromTime,
                toTime,
                toTime,
                size,
                (long) page * size)
            .query((rs, row) ->
                new PaymentRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("checkout_id", UUID.class),
                    rs.getObject("merchant_id", UUID.class),
                    State.valueOf(rs.getString("state")),
                    PaymentMethod.valueOf(rs.getString("method")),
                    rs.getLong("amount"),
                    rs.getString("currency"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()))
            .list();
    return new Page(items, page, size);
  }

  @Operation(summary = "Read payment totals", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/api/v1/payment-stats")
  Stats stats(
      @AuthenticationPrincipal ApiClient client, @RequestParam(required = false) UUID merchantId) {
    UUID scope = scope(client, merchantId);
    Map<String, Long> counts = new LinkedHashMap<>();
    for (State state : State.values()) {
      counts.put(state.name(), 0L);
    }
    long[] succeededAmount = {0};
    long[] total = {0};
    jdbc.sql(
            """
            SELECT state, count(*) AS count, coalesce(sum(amount), 0) AS amount
            FROM payment_attempts WHERE (?::uuid IS NULL OR merchant_id = ?) GROUP BY state
            """)
        .params(scope, scope)
        .query(rs -> {
          String state = rs.getString("state");
          counts.put(state, rs.getLong("count"));
          total[0] += rs.getLong("count");
          if (State.SUCCEEDED.name().equals(state)) {
            succeededAmount[0] = rs.getLong("amount");
          }
        });
    return new Stats(counts, succeededAmount[0], total[0]);
  }

  private static UUID scope(ApiClient client, UUID requested) {
    return client.role() == ApiClient.Role.MERCHANT ? client.merchantId() : requested;
  }
}
