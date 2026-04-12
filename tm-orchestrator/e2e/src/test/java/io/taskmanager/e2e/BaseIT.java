package io.taskmanager.e2e;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URL;
import java.time.Duration;

/**
 * Base class for all Selenium integration tests.
 *
 * Lifecycle:
 *   @BeforeAll  — starts a RemoteWebDriver session against the standalone-chrome container
 *   @AfterAll   — quits the driver (one browser per test class)
 *   @BeforeEach — deletes all browser cookies and clears the Mailpit inbox
 *
 * Connection parameters are read from system properties injected by maven-failsafe-plugin
 * (see e2e/pom.xml). APP_URL is the browser-visible address (used inside the
 * Selenium container). API_URL is the runner-host-visible address for direct
 * java.net.http calls in REST-oriented tests.
 */
public abstract class BaseIT {

    // ── Connection parameters ──────────────────────────────────────────────────

    protected static final String APP_URL =
            System.getProperty("e2e.app.url",      "http://localhost:8080");
    protected static final String API_URL =
            System.getProperty("e2e.api.url",      "http://localhost:8080");
    protected static final String SELENIUM_URL =
            System.getProperty("e2e.selenium.url", "http://localhost:4444");
    protected static final String MAILPIT_URL =
            System.getProperty("e2e.mailpit.url",  "http://localhost:8025");
    protected static final String ADMIN_EMAIL =
            System.getProperty("e2e.admin.email",  "admin@task-manager.local");
    protected static final String ADMIN_PASS =
            System.getProperty("e2e.admin.password","Admin1234!");

    // ── Shared state (one browser session per test class) ─────────────────────

    protected static RemoteWebDriver driver;
    protected static WebDriverWait   wait;
    protected static MailpitClient   mailpit;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeAll
    static void startBrowser() throws Exception {
        ChromeOptions opts = new ChromeOptions();
        opts.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1280,900"
        );
        driver  = new RemoteWebDriver(new URL(SELENIUM_URL), opts);
        wait    = new WebDriverWait(driver, Duration.ofSeconds(25));
        mailpit = new MailpitClient(MAILPIT_URL);
    }

    @AfterAll
    static void closeBrowser() {
        if (driver != null) {
            driver.quit();
            driver = null;
        }
    }

    @BeforeEach
    void resetState() throws Exception {
        driver.manage().deleteAllCookies();
        mailpit.deleteAll();
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    protected void navigate(String path) {
        driver.get(APP_URL + path);
    }

    protected void waitForUrlContains(String fragment) {
        wait.until(ExpectedConditions.urlContains(fragment));
    }

    // ── Element helpers ───────────────────────────────────────────────────────

    protected WebElement waitForId(String id) {
        return wait.until(ExpectedConditions.elementToBeClickable(By.id(id)));
    }

    protected WebElement waitForTestId(String testId) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("[data-testid='" + testId + "']")));
    }

    protected WebElement waitForText(String text) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//*[contains(text()," + xq(text) + ")]")));
    }

    protected boolean isTextPresent(String text) {
        return !driver.findElements(
                By.xpath("//*[contains(text()," + xq(text) + ")]")).isEmpty();
    }

    protected void fillById(String id, String value) {
        WebElement el = waitForId(id);
        el.clear();
        el.sendKeys(value);
    }

    protected void clickButtonByText(String text) {
        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//*[self::button or self::a][contains(normalize-space()," + xq(text) + ")]"))).click();
    }

    protected void clickByTestId(String testId) {
        wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[data-testid='" + testId + "']"))).click();
    }

    /** XPath string-literal quoting — handles values that contain single quotes. */
    private static String xq(String value) {
        return value.contains("'") ? "\"" + value + "\"" : "'" + value + "'";
    }

    // ── Reusable auth flows ───────────────────────────────────────────────────

    /** Registers a new local user and asserts the browser lands on /dashboard. */
    protected void registerUser(String email, String password) {
        navigate("/register");
        fillById("email",           email);
        fillById("password",        password);
        fillById("confirmPassword", password);
        clickButtonByText("Create account");
        waitForUrlContains("/dashboard");
    }

    /** Logs in with email/password and asserts the browser lands on /dashboard. */
    protected void loginLocal(String email, String password) {
        navigate("/login");
        fillById("email",    email);
        fillById("password", password);
        clickButtonByText("Sign in");
        waitForUrlContains("/dashboard");
    }

    /** Clicks the top-nav logout button and asserts the browser lands on /login. */
    protected void logout() {
        wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("[aria-label='Log out']"))).click();
        waitForUrlContains("/login");
    }
}
