package com.tm.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA configuration.
 *
 * @EnableJpaAuditing activates the AuditingEntityListener that populates
 * @CreatedDate and @LastModifiedDate on AuditableEntity subclasses (User, Task)
 * and on Tenant (which uses @EntityListeners directly).
 *
 * @EnableJpaRepositories targets the infrastructure package where all Spring Data
 * JPA repository interfaces live.
 *
 * The Hibernate tenant filter definition (@FilterDef) lives in
 * com.tm.core.domain.package-info.java and is activated per-request by
 * TenantInterceptor (implemented in Session 5). See MULTI_TENANCY.md §4.
 *
 * ddl-auto is set to "validate" in application.yml — Hibernate validates the schema
 * against entities but never modifies it. All schema changes go through Liquibase.
 */
@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.tm.core.infrastructure")
public class JpaConfig {
}