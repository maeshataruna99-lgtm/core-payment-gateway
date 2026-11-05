package com.example.payment.merchant;

import com.example.payment.common.Hashing;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What: Creates a synthetic merchant and API clients for the local demo.
 *
 * <p>Why: The demo must work after one command, without external credentials.
 *
 * <p>Safety: Runs only in the {@code demo} profile. Its keys are dummy values from the environment.
 */
@Configuration
@Profile("demo")
public class DemoDataSeeder {

  private static final UUID MERCHANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final UUID MERCHANT_CLIENT =
      UUID.fromString("00000000-0000-4000-8000-000000000002");
  private static final UUID OPERATOR_CLIENT =
      UUID.fromString("00000000-0000-4000-8000-000000000003");

  @Bean
  ApplicationRunner seedDemoData(
      JdbcClient jdbc,
      @Value("${payment.demo.merchant-api-key}") String merchantKey,
      @Value("${payment.demo.operator-api-key}") String operatorKey) {
    return args -> {
      jdbc.sql("INSERT INTO merchants (id, name) VALUES (?, ?) ON CONFLICT DO NOTHING")
          .params(MERCHANT, "Toko Contoh")
          .update();
      String insertClient =
          """
          INSERT INTO api_clients (id, merchant_id, role, key_hash)
          VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
          """;
      jdbc.sql(insertClient)
          .params(MERCHANT_CLIENT, MERCHANT, "MERCHANT", Hashing.sha256Hex(merchantKey))
          .update();
      jdbc.sql(insertClient)
          .params(OPERATOR_CLIENT, null, "OPERATOR", Hashing.sha256Hex(operatorKey))
          .update();
    };
  }
}
