package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup check: verifies every {@code retention_policy} row with a non-null
 * {@code entity_name} resolves to a classified entity in the registry, and logs
 * a warning for any CONFIDENTIAL or RESTRICTED entity that has no retention policy.
 *
 * <p>The first check (unresolvable policy rows) <em>fails fast</em>: a retention
 * policy that does not map to a classified entity is a data-integrity error.
 *
 * <p>The second check (classified entities without policies) logs a WARNING only,
 * because the five seeded categories are placeholders pending Q7 ratification and
 * not all CONFIDENTIAL/RESTRICTED entities have a corresponding policy row yet.
 * Once Q7 is resolved, all entities should have policies and the warning should
 * be promoted to a hard failure at that time.
 *
 * <p>Disabled when {@code app.privacy.consistency-check.enabled=false}.
 */
@Component
@Order(Integer.MAX_VALUE - 10)
@ConditionalOnProperty(name = "app.privacy.consistency-check.enabled", matchIfMissing = true)
class RetentionStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionStartupCheck.class);

    private final ClassificationRegistry    classificationRegistry;
    private final RetentionPolicyRepository policyRepository;

    RetentionStartupCheck(ClassificationRegistry    classificationRegistry,
                           RetentionPolicyRepository policyRepository) {
        this.classificationRegistry = classificationRegistry;
        this.policyRepository       = policyRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> allEntityNames = classificationRegistry.findAll().stream()
                .map(ClassificationView::entityName)
                .collect(Collectors.toSet());

        List<RetentionPolicyEntity> policies = policyRepository.findAll();

        // Hard-fail check: every policy row with entity_name must map to a classified entity
        List<String> unresolvable = policies.stream()
                .filter(p -> p.getEntityName() != null && !allEntityNames.contains(p.getEntityName()))
                .map(p -> p.getDataCategory() + " → " + p.getEntityName())
                .toList();

        if (!unresolvable.isEmpty()) {
            String message = "Retention policy startup check failed: the following policy rows "
                    + "have entity_name values not present in the classification registry: "
                    + unresolvable;
            log.error("retention_startup_check_failed unresolvable={}", unresolvable);
            throw new IllegalStateException(message);
        }

        // Warning check: CONFIDENTIAL/RESTRICTED entities without retention policies
        Set<String> policyEntityNames = policies.stream()
                .filter(p -> p.getEntityName() != null)
                .map(RetentionPolicyEntity::getEntityName)
                .collect(Collectors.toSet());

        List<ClassificationView> sensitiveEntities = new ArrayList<>();
        sensitiveEntities.addAll(classificationRegistry.findByTier(ClassificationTier.CONFIDENTIAL));
        sensitiveEntities.addAll(classificationRegistry.findByTier(ClassificationTier.RESTRICTED));

        List<String> missingPolicies = sensitiveEntities.stream()
                .filter(cv -> cv.fieldName() == null)
                .map(ClassificationView::entityName)
                .filter(en -> !policyEntityNames.contains(en))
                .distinct()
                .toList();

        if (!missingPolicies.isEmpty()) {
            log.warn("retention_startup_check_warning "
                    + "confidential_or_restricted_entities_without_retention_policy={} "
                    + "action=Add retention_policy rows for these entities once Q7 is ratified",
                    missingPolicies);
        }

        log.info("retention_startup_check_passed policies={} unresolvable=0 missing_warnings={}",
                policies.size(), missingPolicies.size());
    }
}
