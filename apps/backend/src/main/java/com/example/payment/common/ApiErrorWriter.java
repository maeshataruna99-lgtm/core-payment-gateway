package com.example.payment.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Writes an {@link ApiError} from a servlet filter, which runs outside the MVC handler. */
public final class ApiErrorWriter {

  private ApiErrorWriter() {}

  public static void write(
      HttpServletResponse response, JsonMapper mapper, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(),
        new ApiError(status.name(), message, MDC.get(CorrelationIdFilter.MDC_KEY), List.of()));
  }
}
