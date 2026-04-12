package com.tm.core.domain;

/**
 * Thrown when a password reset token is not found, already used, or expired.
 * Mapped to HTTP 400 by GlobalExceptionHandler.
 * See PASSWORD_POLICY.md §4.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}