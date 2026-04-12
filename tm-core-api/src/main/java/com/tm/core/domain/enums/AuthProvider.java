package com.tm.core.domain.enums;

/**
 * Authentication provider for a user account.
 * Immutable after account creation. See DATABASE_SCHEMA.md §users.
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE,
    MICROSOFT
}