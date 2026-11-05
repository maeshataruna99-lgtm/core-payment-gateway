package com.example.payment.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * What: Limits API requests per remote address, in one-minute windows.
 *
 * <p>Why: It slows API key guessing and floods on public endpoints.
 *
 * <p>Tests: {@code RateLimitFilterTest}.
 */
// shortcut: counters live in this process and trust the socket address; move to Redis and a
// trusted proxy header if this ever runs as several instances or behind a load balancer.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

  private final int limit;
  private final Clock clock;
  private final JsonMapper mapper;
  private final Map<String, Integer> counts = new ConcurrentHashMap<>();
  private volatile long window = -1;

  public RateLimitFilter(
      @Value("${payment.rate-limit.requests-per-minute}") int limit,
      Clock clock,
      JsonMapper mapper) {
    this.limit = limit;
    this.clock = clock;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long current = clock.millis() / 60_000;
    if (current != window) {
      window = current;
      counts.clear();
    }
    if (counts.merge(request.getRemoteAddr(), 1, Integer::sum) > limit) {
      ApiErrorWriter.write(
          response, mapper, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
      return;
    }
    chain.doFilter(request, response);
  }
}
