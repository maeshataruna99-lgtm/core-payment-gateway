package com.example.payment.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes the API in the generated OpenAPI contract and labels it as a simulator. */
@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI paymentGatewayOpenApi() {
    return new OpenAPI()
        .components(
            new Components()
                .addSecuritySchemes(
                    "apiKey",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-Api-Key")))
        .info(
            new Info()
                .title("PayGate Sandbox API")
                .version("v1")
                .description(
                    "SANDBOX: a payment gateway simulator. It processes no real funds and is not"
                        + " compatible with any real provider's API."));
  }
}
