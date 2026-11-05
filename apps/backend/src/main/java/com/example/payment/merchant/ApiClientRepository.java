package com.example.payment.merchant;

import com.example.payment.common.Hashing;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Finds API clients by key. Keys are stored only as hashes. */
@Repository
public class ApiClientRepository {

  private final JdbcClient jdbc;

  public ApiClientRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ApiClient> findByApiKey(String apiKey) {
    return jdbc.sql("SELECT id, merchant_id, role FROM api_clients WHERE key_hash = ?")
        .param(Hashing.sha256Hex(apiKey))
        .query((rs, row) ->
            new ApiClient(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                ApiClient.Role.valueOf(rs.getString("role"))))
        .optional();
  }
}
