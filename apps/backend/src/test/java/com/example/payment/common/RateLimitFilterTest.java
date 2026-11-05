package com.example.payment.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

  private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");

  private static int call(RateLimitFilter filter, String path, String address) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr(address);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response.getStatus();
  }

  private static RateLimitFilter filter(Instant now) {
    return new RateLimitFilter(2, Clock.fixed(now, ZoneOffset.UTC), JsonMapper.builder().build());
  }

  @Test
  void blocksAfterLimitPerAddress() throws Exception {
    RateLimitFilter filter = filter(T0);
    assertEquals(200, call(filter, "/api/v1/x", "10.0.0.1"));
    assertEquals(200, call(filter, "/api/v1/x", "10.0.0.1"));
    assertEquals(429, call(filter, "/api/v1/x", "10.0.0.1"));
    assertEquals(200, call(filter, "/api/v1/x", "10.0.0.2"));
  }

  @Test
  void healthIsNotLimited() throws Exception {
    RateLimitFilter filter = filter(T0);
    for (int i = 0; i < 5; i++) {
      assertEquals(200, call(filter, "/actuator/health", "10.0.0.1"));
    }
  }
}
