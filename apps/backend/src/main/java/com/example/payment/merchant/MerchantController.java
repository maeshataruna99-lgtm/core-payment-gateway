package com.example.payment.merchant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What: Tells the dashboard who is signed in and, for staff, which merchants exist.
 *
 * <p>Safety: Merchant listing is limited to staff in the security rules. A key is never returned.
 *
 * <p>Tests: {@code DashboardApiIntegrationTest}.
 */
@RestController
class MerchantController {

  private final JdbcClient jdbc;

  MerchantController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The caller of this request. */
  record Me(UUID clientId, ApiClient.Role role, UUID merchantId) {}

  /** A merchant. */
  record Merchant(UUID id, String name) {}

  @Operation(
      summary = "Read the signed-in client",
      security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/api/v1/me")
  Me me(@AuthenticationPrincipal ApiClient client) {
    return new Me(client.id(), client.role(), client.merchantId());
  }

  @Operation(summary = "List merchants", security = @SecurityRequirement(name = "apiKey"))
  @GetMapping("/api/v1/merchants")
  List<Merchant> merchants() {
    return jdbc.sql("SELECT id, name FROM merchants ORDER BY name, id LIMIT 200")
        .query((rs, row) -> new Merchant(rs.getObject("id", UUID.class), rs.getString("name")))
        .list();
  }
}
