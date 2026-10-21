package com.aether.api;

import org.springframework.http.HttpStatus;

/** A bounded public error with no provider payloads, tokens or database details. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
}
