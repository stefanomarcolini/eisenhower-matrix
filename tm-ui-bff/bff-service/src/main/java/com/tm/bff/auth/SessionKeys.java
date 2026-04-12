package com.tm.bff.auth;

/**
 * Session attribute key constants (CODING_PATTERNS.md §3).
 * All session reads and writes must use these constants — never inline strings.
 */
public final class SessionKeys {

    /** The app-issued JWT from Core API. Present only when the user is fully authenticated. */
    public static final String APP_JWT = "APP_JWT";

    /** Tenant UUID string. Present when fully authenticated. */
    public static final String TENANT_ID = "TENANT_ID";

    /** Boolean flag set to {@code true} during the MFA step (between primary auth and TOTP verify). */
    public static final String MFA_PENDING = "MFA_PENDING";

    /** User UUID string stored during MFA step so the TOTP verify call can identify the user. */
    public static final String PENDING_USER_ID = "PENDING_USER_ID";

    /** Boolean flag — {@code true} when password age exceeds the warning threshold. */
    public static final String PASSWORD_WARNING = "PASSWORD_WARNING";

    /** Counter for failed MFA attempts in the current partial session (see CODING_PATTERNS.md §17). */
    public static final String MFA_ATTEMPTS = "MFA_ATTEMPTS";

    private SessionKeys() {}
}
