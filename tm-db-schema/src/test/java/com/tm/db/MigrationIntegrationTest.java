package com.tm.db;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Validates that all {@value #CHANGESET_COUNT} Liquibase changesets apply cleanly
 * and that each rollback block works independently.
 * Runs against a real PostgreSQL container — no mocking.
 *
 * The Docker image is controlled by the Maven property {@code postgresql.test.image}
 * (default: postgres:17-alpine, matches DATABASE_SCHEMA.md). Override at CI time:
 *   mvn verify -Dpostgresql.test.image=postgres:18-alpine
 */
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
class MigrationIntegrationTest {

    /** Total number of Liquibase changesets. Update when a new changeset is added. */
    private static final int CHANGESET_COUNT = 8;

    /** Reusable context — avoids repeated allocation inside the rollback loop. */
    private static final Contexts PROD_CONTEXT = new Contexts("prod");

    /** Empty label expression — all changesets run regardless of label. */
    private static final LabelExpression NO_LABELS = new LabelExpression();

    // BCrypt-shaped placeholder for changeset 007. Cryptographic correctness is not
    // required here — only the VARCHAR(255) column constraint matters at migration time.
    private static final String TEST_BCRYPT_HASH =
        "$2a$04$TESTONLYxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        System.getProperty("postgresql.test.image", "postgres:17-alpine"))
        .withDatabaseName("tm_test")
        .withUsername("tm")
        .withPassword("testpass");

    private Liquibase liquibase;

    @BeforeEach
    void setUp() throws Exception {
        Database database = DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(
                postgres.createConnection("")));
        liquibase = new Liquibase(
            "db/changelog/db.changelog-master.yaml",
            new ClassLoaderResourceAccessor(),
            database);
        liquibase.setChangeLogParameter("BOOTSTRAP_ADMIN_BCRYPT_HASH", TEST_BCRYPT_HASH);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (liquibase != null) {
            liquibase.close(); // closes the underlying JDBC connection
        }
    }

    @Test
    @Order(1)
    void allMigrationsApplyCleanly() {
        assertDoesNotThrow(
            () -> liquibase.update(PROD_CONTEXT),
            "All " + CHANGESET_COUNT + " changesets must apply without error");
    }

    @Test
    @Order(2)
    void eachChangesetRollbackBlockWorks() {
        assertDoesNotThrow(() -> {
            // update() is idempotent — changesets already applied by @Order(1) are skipped.
            liquibase.update(PROD_CONTEXT);

            // Roll back ONE changeset at a time (rollbackCount 1, repeated per changeset).
            // This validates each rollback block independently — a broken rollback on changeset
            // 003 would NOT be caught by a single rollback(N) call if 008–004 all succeeded.
            // Per IMPLEMENTATION_ROADMAP.md §Session 3.
            // Note: for loop used instead of IntStream because rollback() throws LiquibaseException
            // (checked), which cannot propagate through Stream.forEach without unchecked wrapping.
            for (int i = 0; i < CHANGESET_COUNT; i++) {
                liquibase.rollback(1, PROD_CONTEXT, NO_LABELS);
            }
        }, "Each of the " + CHANGESET_COUNT + " changeset rollback blocks must work independently");
    }
}