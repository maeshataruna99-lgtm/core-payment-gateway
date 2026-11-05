package com.example.payment.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  private String run(String headerValue) throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (headerValue != null) {
      request.addHeader(CorrelationIdFilter.HEADER, headerValue);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response.getHeader(CorrelationIdFilter.HEADER);
  }

  @Test
  void reusesWellFormedId() throws Exception {
    assertEquals("abc-123", run("abc-123"));
  }

  @Test
  void replacesIdThatCouldInjectLogLines() throws Exception {
    String malicious = "abc\nFAKE LOG LINE";
    assertNotEquals(malicious, run(malicious));
  }

  @Test
  void generatesIdWhenMissing() throws Exception {
    assertEquals(36, run(null).length());
  }

  @Test
  void clearsMdcAfterRequest() throws Exception {
    run("abc-123");
    assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
  }
}
