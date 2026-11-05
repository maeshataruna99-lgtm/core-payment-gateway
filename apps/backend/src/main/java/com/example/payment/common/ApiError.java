package com.example.payment.common;

import java.util.List;

/**
 * The error body returned by every endpoint.
 *
 * @param code stable machine-readable code
 * @param message human-readable summary that exposes no internal detail
 * @param traceId correlation ID of the request
 * @param fieldErrors validation errors, empty when none apply
 */
public record ApiError(String code, String message, String traceId, List<FieldError> fieldErrors) {

  /** A validation error on one request field. */
  public record FieldError(String field, String message) {}
}
