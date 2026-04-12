package com.tm.core.application;

import com.tm.core.domain.PasswordPolicyViolationException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces server-side password strength rules from PASSWORD_POLICY.md §1.
 * Throws PasswordPolicyViolationException (HTTP 422) when rules are violated.
 *
 * The common-passwords list is loaded from classpath:security/common-passwords.txt
 * (one password per line). The file is not committed to version control; provide it
 * from a public source such as SecLists top-10k-most-common.txt.
 */
@Service
public class PasswordValidationService {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 128;
    private static final String SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;':\",./<>?";
    // Pre-built Set for O(1) lookup instead of O(n) String.indexOf per character.
    private static final Set<Integer> SPECIAL_CHAR_CODES =
            SPECIAL_CHARS.chars().boxed().collect(Collectors.toUnmodifiableSet());

    private final Set<String> commonPasswords;

    public PasswordValidationService() {
        this.commonPasswords = loadCommonPasswords();
    }

    public void validate(String password) {
        List<String> violations = new ArrayList<>();

        if (password.length() < MIN_LENGTH) {
            violations.add("Password must be at least " + MIN_LENGTH + " characters.");
        }
        if (password.length() > MAX_LENGTH) {
            violations.add("Password must not exceed " + MAX_LENGTH + " characters.");
        }
        if (!password.chars().anyMatch(Character::isUpperCase)) {
            violations.add("Password must contain at least one uppercase letter.");
        }
        if (!password.chars().anyMatch(Character::isLowerCase)) {
            violations.add("Password must contain at least one lowercase letter.");
        }
        if (!password.chars().anyMatch(Character::isDigit)) {
            violations.add("Password must contain at least one digit.");
        }
        boolean hasSpecial = password.chars().anyMatch(SPECIAL_CHAR_CODES::contains);
        if (!hasSpecial) {
            violations.add("Password must contain at least one special character ("
                    + SPECIAL_CHARS + ").");
        }
        if (commonPasswords.contains(password.toLowerCase())) {
            violations.add("Password is too common. Please choose a more unique password.");
        }

        if (!violations.isEmpty()) {
            throw new PasswordPolicyViolationException(violations);
        }
    }

    private Set<String> loadCommonPasswords() {
        try (InputStream is = getClass().getResourceAsStream("/security/common-passwords.txt")) {
            if (is == null) {
                return Set.of();
            }
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            // Non-fatal: common passwords check is skipped when the resource is unavailable.
            return Set.of();
        }
    }
}