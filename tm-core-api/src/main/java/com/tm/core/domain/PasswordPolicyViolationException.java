package com.tm.core.domain;

import java.util.List;

/**
 * Thrown when a submitted password violates one or more rules in PASSWORD_POLICY.md §1.
 * Mapped to HTTP 422 by GlobalExceptionHandler.
 */
public class PasswordPolicyViolationException extends RuntimeException {

    private final List<String> violations;

    public PasswordPolicyViolationException(List<String> violations) {
        super("Password policy violations: " + violations);
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }
}