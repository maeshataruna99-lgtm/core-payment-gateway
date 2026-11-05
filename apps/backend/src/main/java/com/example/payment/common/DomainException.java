package com.example.payment.common;

import org.springframework.http.HttpStatus;

/** A rejected request whose code and message are safe to return to the client. */
public class DomainException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final String field;

  public DomainException(HttpStatus status, String code, String message) {
    this(status, code, message, null);
  }

  public DomainException(HttpStatus status, String code, String message, String field) {
    super(message);
    this.status = status;
    this.code = code;
    this.field = field;
  }

  /** A validation failure on one request field. */
  public static DomainException invalidField(String field, String message) {
    return new DomainException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, field);
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String field() {
    return field;
  }
}
