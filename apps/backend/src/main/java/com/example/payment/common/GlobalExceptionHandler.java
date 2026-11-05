package com.example.payment.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * What: Turns every exception raised by a controller into an {@link ApiError} response.
 *
 * <p>Why: Clients get one error format, and stack traces never reach them.
 *
 * <p>How: Extends Spring's handler for standard MVC errors, adds field errors for failed
 * validation, and maps anything unexpected to a generic 500.
 *
 * <p>Safety: Unexpected errors are logged on the server only. Their message is never returned.
 *
 * <p>Tests: {@code GlobalExceptionHandlerTest}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<ApiError.FieldError> fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new ApiError.FieldError(e.getField(), e.getDefaultMessage()))
            .toList();
    return respond(status, "VALIDATION_FAILED", "Request validation failed.", fields);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String reason =
        status instanceof HttpStatus known ? known.getReasonPhrase() : "Request failed.";
    String code = status instanceof HttpStatus known ? known.name() : "REQUEST_FAILED";
    return respond(status, code, reason, List.of());
  }

  /** Handles anything no other handler covers. */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception ex) {
    LOG.error("Unexpected error", ex);
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "An unexpected error occurred.",
        List.of());
  }

  private static ResponseEntity<Object> respond(
      HttpStatusCode status, String code, String message, List<ApiError.FieldError> fields) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ApiError(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), fields));
  }
}
