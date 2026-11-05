package com.example.payment.merchant;

import java.util.UUID;

/**
 * A caller of the API.
 *
 * @param merchantId the merchant this client acts for, null for staff roles
 */
public record ApiClient(UUID id, UUID merchantId, Role role) {

  /** What a client may do. */
  public enum Role {
    MERCHANT,
    OPERATOR,
    ADMIN
  }

  /** How this client is named in audit records. */
  public String actor() {
    return role.name().toLowerCase(java.util.Locale.ROOT) + ":" + id;
  }
}
