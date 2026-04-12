package com.tm.core.domain;

/**
 * Thrown on duplicate-resource conflicts (e.g. email already registered, wrong auth provider).
 * Mapped to HTTP 409 by GlobalExceptionHandler.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}