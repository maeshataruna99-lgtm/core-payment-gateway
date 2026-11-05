package com.example.payment.security;

import com.example.payment.common.ApiErrorWriter;
import com.example.payment.merchant.ApiClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * What: Defines who may call which endpoint.
 *
 * <p>How: Everything requires authentication unless listed here. Requests are stateless and use no
 * cookies, so CSRF protection does not apply.
 *
 * <p>Safety: Public checkout and payment routes are protected by a per-checkout token in their
 * services.
 *
 * <p>Tests: {@code CheckoutApiIntegrationTest}.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ApiClientRepository clients, JsonMapper mapper) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth ->
            auth.requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/checkout-sessions/*")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/checkout-sessions")
                .hasRole("MERCHANT")
                .requestMatchers(HttpMethod.POST, "/api/v1/payments")
                .permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/payments/*")
                .permitAll()
                .requestMatchers("/api/v1/sandbox/**")
                .hasAnyRole("OPERATOR", "ADMIN")
                .anyRequest()
                .authenticated())
        .exceptionHandling(e ->
            e.authenticationEntryPoint((request, response, ex) ->
                    ApiErrorWriter.write(
                        response, mapper, HttpStatus.UNAUTHORIZED, "A valid API key is required."))
                .accessDeniedHandler((request, response, ex) ->
                    ApiErrorWriter.write(
                        response,
                        mapper,
                        HttpStatus.FORBIDDEN,
                        "This API key may not perform this action.")))
        .addFilterBefore(new ApiKeyAuthFilter(clients), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
