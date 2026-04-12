-- Seed data for integration tests.
-- Provides the fixed UUIDs that Liquibase seed migrations would normally insert.
-- See tm-db-schema/src/main/resources/db/changelog/005-seed-roles.yaml
-- and 006-seed-tenant.yaml for canonical values.

INSERT INTO roles (id, name)
VALUES ('00000000-0000-0000-0000-000000000010', 'STANDARD'),
       ('00000000-0000-0000-0000-000000000011', 'ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO tenants (id, name, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Test Tenant', NOW())
ON CONFLICT DO NOTHING;
