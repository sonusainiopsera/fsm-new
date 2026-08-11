package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Startup consistency check: every CONFIDENTIAL or RESTRICTED entity in the
 * classification registry must have a corresponding {@code retention_policy} row.
 *
 * <p>Fails fast with a single aggregated {@link IllegalStateException} listing all
 * missing entity names. This prevents deploying a configuration where sensitive
 * data has no defined retention schedule (GDPR/CCPA Phase 4 exit gate).
 *
 * <p>Disabled in the {@code test} profile to avoid Testcontainers dependency on a
 * fully seeded registry. All other profiles run this check on startup.
 */
@Component
@Profile("!test")
class RetentionPolicyStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyStartupCheck.class);

    private final ClassificationRegistry classificationRegistry;
    private final RetentionPolicyRepository repository;

    RetentionPolicyStartupCheck(ClassificationRegistry classificationRegistry,
                                 RetentionPolicyRepository repository) {
        this.classificationRegistry = classificationRegistry;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("retention-policy.startup-check: scanning CONFIDENTIAL and RESTRICTED entities");

        List<String> sensitiveEntities = Stream.concat(
                classificationRegistry.findByTier(ClassificationTier.CONFIDENTIAL).stream(),
                classificationRegistry.findByTier(ClassificationTier.RESTRICTED).stream()
        ).map(ClassificationView::entityName).distinct().sorted().collect(Collectors.toList());

        List<String> missing = sensitiveEntities.stream()
                .filter(entityName -> !repository.existsByEntityName(entityName))
                .collect(Collectors.toList());

        if (missing.isEmpty()) {
            log.info("retention-policy.startup-check: OK — {} sensitive entities all have retention policies",
                    sensitiveEntities.size());
            return;
        }

        String message = "Retention policy missing for CONFIDENTIAL/RESTRICTED entities: " + missing
                + ". Add retention_policy rows for these entities or remove their @DataClassification annotations.";
        log.error("retention-policy.startup-check: FAILED — {}", message);
        throw new IllegalStateException(message);
    }
}
