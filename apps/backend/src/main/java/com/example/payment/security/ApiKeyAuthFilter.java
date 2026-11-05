package com.example.payment.security;

import com.example.payment.merchant.ApiClient;
import com.example.payment.merchant.ApiClientRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * What: Authenticates a request from its {@code X-Api-Key} header.
 *
 * <p>How: A valid key sets the security context. An invalid key sets nothing, so the authorization
 * rules treat the request as anonymous.
 *
 * <p>Safety: The key is hashed before lookup and never logged.
 *
 * <p>Tests: {@code CheckoutApiIntegrationTest}.
 */
class ApiKeyAuthFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Api-Key";

  private final ApiClientRepository clients;

  ApiKeyAuthFilter(ApiClientRepository clients) {
    this.clients = clients;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key != null) {
      clients.findByApiKey(key).ifPresent(ApiKeyAuthFilter::authenticate);
    }
    chain.doFilter(request, response);
  }

  private static void authenticate(ApiClient client) {
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + client.role()));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(client, null, authorities));
  }
}
