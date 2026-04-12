/**
 * Domain entities for the Task Manager Core API.
 *
 * The Hibernate tenant filter definition is declared here once and referenced
 * by @Filter on every tenant-scoped entity (User, Task, TaskHistory).
 * See MULTI_TENANCY.md §4.
 */
@FilterDef(
    name = "tenantFilter",
    parameters = @ParamDef(name = "tenantId", type = String.class)
)
package com.tm.core.domain;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;