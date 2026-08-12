package com.fieldservice.common.config;

import org.springframework.context.annotation.Configuration;

/**
 * Hibernate Envers configuration.
 *
 * <p>Runtime properties (audit table suffix, revision field names, etc.) are
 * declared in {@code application.yml} under
 * {@code spring.jpa.properties.hibernate.envers.*}.
 *
 * <p>The custom revision entity {@link com.fieldservice.common.audit.RevisionInfo}
 * is picked up automatically by Hibernate Envers via the {@code @RevisionEntity}
 * annotation — no explicit registration is required here.
 *
 * <p>Audited entities are annotated with
 * {@link org.hibernate.envers.Audited} at the class level, which causes Envers
 * to track INSERT, UPDATE, and DELETE operations and write audit rows to the
 * corresponding {@code _AUD} table.
 */
@Configuration
public class EnversConfig {
    // Configuration is intentionally minimal: all Envers properties live in
    // application.yml and the RevisionInfo entity is self-registering.
}
