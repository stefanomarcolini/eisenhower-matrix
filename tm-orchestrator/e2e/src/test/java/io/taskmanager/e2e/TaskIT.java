package io.taskmanager.e2e;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Task lifecycle E2E scenario (Scenario 7 from IMPLEMENTATION_ROADMAP.md Session 16).
 *
 * 7. Create task → delete task → verify absent from list and matrix (soft-delete end-to-end)
 *
 * Verifies that a deleted task disappears from both the matrix and the list views.
 */
class TaskIT extends BaseIT {

    private static final String SUFFIX = Long.toHexString(System.currentTimeMillis());

    @Test
    void createAndDeleteTaskVerifyAbsent() {
        String email     = "task-" + SUFFIX + "@test.io";
        String password  = "Password1!";
        String taskTitle = "Delete Me " + SUFFIX;

        registerUser(email, password);

        // ── Create the task via the New Task button ───────────────────────────
        clickByTestId("new-task-btn");
        waitForTestId("task-dialog");
        fillById("task-title", taskTitle);
        clickButtonByText("Create");

        // Wait for dialog to close and task to appear in the matrix
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='task-dialog']")));
        waitForText(taskTitle);

        // ── Open the task to edit/delete it ──────────────────────────────────
        // Click on the task card inside the matrix (the task title is a clickable element)
        wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//*[contains(text()," + xq(taskTitle) + ")]"))).click();
        waitForTestId("task-dialog");

        // Delete the task
        clickByTestId("delete-task");

        // Dialog closes after deletion
        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.cssSelector("[data-testid='task-dialog']")));

        // ── Verify absent from matrix ─────────────────────────────────────────
        assertFalse(isTextPresent(taskTitle),
                "Deleted task title should not appear in the matrix");

        // ── Switch to list view and verify absent there too ───────────────────
        clickByTestId("view-list");
        waitForTestId("task-list");
        assertFalse(isTextPresent(taskTitle),
                "Deleted task title should not appear in the list view");
    }

    /** XPath string-literal quoting — delegates to BaseIT equivalent for reuse in lambdas. */
    private static String xq(String value) {
        return value.contains("'") ? "\"" + value + "\"" : "'" + value + "'";
    }
}
