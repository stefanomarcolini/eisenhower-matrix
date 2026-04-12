package com.tm.core.application;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP-based MFA service.
 * Secrets are AES-256/GCM encrypted before storage in users.mfa_secret.
 * See AUTH_CONFIG.md §7.
 */
@Service
public class MfaService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String ISSUER = "Task Manager";

    // Singleton: SecureRandom instances are thread-safe and expensive to seed.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec encryptionKey;
    private final DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final DefaultCodeVerifier codeVerifier = new DefaultCodeVerifier(
            new DefaultCodeGenerator(), new SystemTimeProvider());

    public MfaService(@Value("${app.mfa.encryption-key}") String encryptionKeyBase64) {
        this.encryptionKey = new SecretKeySpec(
                Base64.getDecoder().decode(encryptionKeyBase64), "AES");
    }

    /** Generates a new Base32 TOTP secret. Must be encrypted before storage. */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /** Builds an otpauth:// URI for QR code display. Uses the plaintext secret. */
    public String buildOtpAuthUri(String plainSecret, String email) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(plainSecret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        return data.getUri();
    }

    /**
     * Verifies a TOTP code against the encrypted secret stored in the DB.
     * Decrypts before verifying.
     */
    public boolean verifyCode(String encryptedSecret, String code) {
        String plainSecret = decrypt(encryptedSecret);
        return codeVerifier.isValidCode(plainSecret, code);
    }

    /**
     * AES-256/GCM encrypt. Output format: Base64(IV || ciphertext+tag).
     * The 12-byte IV is prepended so decrypt can extract it.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("MFA secret encryption failed", e);
        }
    }

    /** AES-256/GCM decrypt. Expects Base64(IV || ciphertext+tag). */
    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("MFA secret decryption failed", e);
        }
    }
}