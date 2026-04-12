package com.tm.core.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends transactional emails via Spring Mail (JavaMailSender).
 * Dev: Mailpit captures all emails at http://localhost:8025 (no credentials needed).
 * See AUTH_CONFIG.md §12 and INFRASTRUCTURE_SPEC.md §5.
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from-address}")
    private String fromAddress;

    @Value("${app.email.from-name}")
    private String fromName;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * Sends a password reset email containing the raw (unhashed) token.
     * The raw token is never stored — only its SHA-256 hash is persisted.
     */
    public void sendPasswordResetEmail(String toAddress, String rawToken) {
        String resetUrl = baseUrl + "/auth/reset-password?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromName + " <" + fromAddress + ">");
        message.setTo(toAddress);
        message.setSubject("Reset your Task Manager password");
        message.setText(
                "You requested a password reset.\n\n"
                + "Click the link below to reset your password (valid for 1 hour):\n"
                + resetUrl + "\n\n"
                + "If you did not request this, you can safely ignore this email.\n"
        );
        mailSender.send(message);
    }
}