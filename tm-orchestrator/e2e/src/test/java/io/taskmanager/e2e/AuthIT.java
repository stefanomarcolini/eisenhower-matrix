package io.taskmanager.e2e;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auth E2E scenarios (Scenarios 1–4 from IMPLEMENTATION_ROADMAP.md Session 16).
 *
 * 1. Local register → login → create task → verify in matrix (happy-path full stack)
 * 2. Login with mock OAuth2 (Google) — OAuth2 OIDC flow via navikt/mock-oauth2-server
 * 3. MFA enable → logout → login → TOTP verify (full MFA lifecycle)
 * 4. Forgot password → reset via Mailpit link → login with new password
 *
 * Emails are captured by Mailpit (SMTP port 1025); retrieved via the Mailpit
 * REST API through {@link MailpitClient}.
 *
 * Each test uses a unique e-mail suffix derived from the current time to avoid
 * clashing with users that persist in the database across the test run.
 */
class AuthIT extends BaseIT {

    private static final String SUFFIX = Long.toHexString(System.currentTimeMillis());

    // ── Scenario 1: register → login → create task → verify in matrix ─────────

    @Test
    void registerLoginCreateTaskVerifyInMatrix() {
        String email    = "auth1-" + SUFFIX + "@test.io";
        String password = "Password1!";
        String taskTitle = "My E2E Task " + SUFFIX;

        // Register new user
        registerUser(email, password);

        // Dashboard should show the task matrix
        waitForTestId("task-matrix");

        // Open "New Task" dialog (MEDIUM/MEDIUM defaults)
        clickByTestId("new-task-btn");
        waitForTestId("task-dialog");

        // Fill task title and submit
        fillById("task-title", taskTitle);
        clickButtonByText("Create");

        // Dialog closes; task title appears somewhere in the matrix
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='task-dialog']")));
        waitForText(taskTitle);

        // Sanity check the complete local-account lifecycle in one flow.
        logout();
        loginLocal(email, password);
        waitForTestId("task-matrix");
    }

    // ── Scenario 2: OAuth2 login via mock-oauth2-server (Google) ─────────────
    //
    // The navikt/mock-oauth2-server runs at http://mock-oauth2:8080 inside Docker.
    // Spring Security's authorization-uri for Google is discovered from the issuer-uri
    // (http://mock-oauth2:8080/default), so the browser — also inside Docker on
    // tm-network — can reach it directly.
    //
    // The mock-oauth2-server v2.x authorization page presents a simple form:
    //   <input id="username" name="username">  (becomes the OIDC 'sub' and 'email' claims)
    //   <button type="submit">Sign-in</button>

    @Test
    void loginWithMockOAuth2Google() {
        navigate("/login");

        // Click the "Continue with Google" button — BFF redirects to mock-oauth2
        clickButtonByText("Continue with Google");

        // On the mock-oauth2 authorize page, enter a fake subject/email
        String mockEmail = "oauth2user-" + SUFFIX + "@test.io";
        WebElement usernameInput = waitForFirstClickable(List.of(
                By.id("username"),
                By.cssSelector("input[name='username']"),
                By.id("subject"),
                By.cssSelector("input[name='subject']"),
                By.cssSelector("input[type='text']")
        ));
        usernameInput.clear();
        usernameInput.sendKeys(mockEmail);

        // Submit the mock login form (markup varies across mock-oauth2 versions).
        submitFirstAvailable(List.of(
                By.cssSelector("button[type='submit']"),
                By.cssSelector("input[type='submit']"),
                By.cssSelector("button[name='login']"),
                By.cssSelector("button[id='login']"),
                By.xpath("//button[contains(translate(normalize-space(), 'SIGNIN', 'signin'), 'sign in')]")
        ), usernameInput);

        // BFF exchanges the code with mock-oauth2, creates a session, redirects
        waitForUrlContains("/dashboard");
        waitForTestId("task-matrix");
    }

    private WebElement waitForFirstClickable(List<By> selectors) {
        return wait.until(driver -> {
            for (By selector : selectors) {
                List<WebElement> found = driver.findElements(selector);
                if (!found.isEmpty()) {
                    WebElement candidate = found.get(0);
                    if (candidate.isDisplayed() && candidate.isEnabled()) {
                        return candidate;
                    }
                }
            }
            return null;
        });
    }

    private void submitFirstAvailable(List<By> selectors, WebElement fallbackInput) {
        for (By selector : selectors) {
            List<WebElement> found = driver.findElements(selector);
            if (!found.isEmpty()) {
                WebElement button = found.get(0);
                if (button.isDisplayed() && button.isEnabled()) {
                    button.click();
                    return;
                }
            }
        }
        fallbackInput.sendKeys(Keys.ENTER);
    }

    // ── Scenario 3: MFA enable → logout → login → TOTP verify ────────────────

    @Test
    void mfaEnableAndLoginWithTotp() throws Exception {
        String email    = "mfa-" + SUFFIX + "@test.io";
        String password = "Password1!";

        // Register and land on dashboard
        registerUser(email, password);

        // Navigate to Settings → MFA section
        navigate("/settings");
        waitForTestId("mfa-section");

        // Enable MFA — opens MfaEnrollDialog
        clickButtonByText("Enable MFA");
        waitForTestId("mfa-enroll-dialog");

        // Expand the "Can't scan?" details to reveal the raw TOTP secret
        WebElement summary = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid='mfa-enroll-dialog'] details > summary")));
        summary.click();

        // Read the secret from the <p> inside <details>
        WebElement secretEl = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='mfa-enroll-dialog'] details p")));
        String totpSecret = secretEl.getText().trim();
        assertFalse(totpSecret.isBlank(), "TOTP secret must not be empty");

        // Generate first TOTP code and confirm enrollment
        String enrollCode = generateTotp(totpSecret);
        fillById("mfa-code", enrollCode);
        clickButtonByText("Activate MFA");

        // Dialog disappears — MFA is now active
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='mfa-enroll-dialog']")));

        // Logout
        logout();

        // Login again — should be redirected to MFA verify page
        navigate("/login");
        fillById("email",    email);
        fillById("password", password);
        clickButtonByText("Sign in");
        waitForUrlContains("/mfa/verify");

        // Generate a fresh code and verify. Retry with adjacent 30s windows to absorb CI clock skew.
        verifyMfaAndWaitForDashboard(totpSecret);
    }

    // ── Scenario 4: forgot password → Mailpit reset → login ──────────────────

    @Test
    void forgotPasswordResetAndLogin() throws Exception {
        String email       = "reset-" + SUFFIX + "@test.io";
        String password    = "Password1!";
        String newPassword = "NewPass99!";

        // Register so the account exists
        registerUser(email, password);
        logout();

        // Request a password reset
        navigate("/forgot-password");
        fillById("email", email);
        clickButtonByText("Send reset link");

        // UI shows the confirmation screen
        waitForText("Check your email");

        // Retrieve the reset email from Mailpit
        String messageId = mailpit.pollForEmail(email, 30);
        String body      = mailpit.getMessageBody(messageId);

        // Extract the reset URL — Core API sends APP_BASE_URL + /auth/reset-password?token=...
        // In CI the APP_BASE_URL is http://frontend-bff:8080 so the link is reachable by
        // the Selenium browser inside Docker.
        String resetLink = mailpit.extractLink(body, "https?://[^\\s]*/auth/reset-password[^\\s]*");

        // Navigate directly to the reset link
        driver.get(resetLink);

        // Fill the new password form
        fillById("newPassword",        newPassword);
        fillById("confirmPassword",    newPassword);
        clickButtonByText("Set new password");

        // Redirected to /login after successful reset
        waitForUrlContains("/login");

        // Log in with the new password
        loginLocal(email, newPassword);
    }

    // ── TOTP helper ───────────────────────────────────────────────────────────

    /**
     * Generates a TOTP code for the current 30-second window using the provided
     * base32-encoded secret. Uses the same {@code dev.samstevens.totp} library
     * as the Core API, so the generated codes are guaranteed to be accepted.
     */
    private static String generateTotp(String secret) throws Exception {
        SystemTimeProvider timeProvider = new SystemTimeProvider();
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long counter = Math.floorDiv(timeProvider.getTime(), 30L);
        return generator.generate(secret, counter);
    }

    private static String generateTotp(String secret, int counterOffset) throws Exception {
        SystemTimeProvider timeProvider = new SystemTimeProvider();
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long counter = Math.floorDiv(timeProvider.getTime(), 30L) + counterOffset;
        return generator.generate(secret, counter);
    }

    private void verifyMfaAndWaitForDashboard(String secret) throws Exception {
        List<String> candidateCodes = List.of(
                generateTotp(secret),
                generateTotp(secret, -1),
                generateTotp(secret, 1)
        );

        for (int i = 0; i < candidateCodes.size(); i++) {
            fillById("code", candidateCodes.get(i));
            clickButtonByText("Verify");

            try {
                waitForUrlContains("/dashboard");
                return;
            } catch (org.openqa.selenium.TimeoutException e) {
                if (!driver.getCurrentUrl().contains("/mfa/verify") || i == candidateCodes.size() - 1) {
                    throw e;
                }
            }
        }
    }
}
