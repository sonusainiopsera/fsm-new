package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.DataClassification;
import jakarta.persistence.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Startup check that compares {@code @DataClassification} annotations in production code
 * against the {@code data_classification} registry table.
 *
 * <p>Fails fast if any annotated element (type or field) has no registry row.
 * Orphaned rows (registry rows whose entity or field no longer exists in code) are
 * reported as warnings only — they may be intentional during an expand/contract migration.
 *
 * <p>Disabled when {@code app.privacy.consistency-check.enabled=false} (e.g. in tests).
 */
@Component
@ConditionalOnProperty(name = "app.privacy.consistency-check.enabled", matchIfMissing = true)
class ClassificationConsistencyCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClassificationConsistencyCheck.class);

    private final ClassificationRegistry registry;

    @Value("${app.privacy.scan-packages:com.fieldservice}")
    private String scanPackages;

    ClassificationConsistencyCheck(ClassificationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Set<AnnotatedElement> codeElements = scanAnnotatedElements();
        Set<AnnotatedElement> registryElements = loadRegistryElements();

        List<String> violations = new ArrayList<>();
        List<String> orphans    = new ArrayList<>();

        for (AnnotatedElement ce : codeElements) {
            if (!registryElements.contains(ce)) {
                violations.add("MISSING registry row for annotated element: "
                        + ce.entityName() + (ce.fieldName() != null ? "." + ce.fieldName() : ""));
            }
        }
        for (AnnotatedElement re : registryElements) {
            if (!codeElements.contains(re)) {
                orphans.add("ORPHANED registry row (no annotation): "
                        + re.entityName() + (re.fieldName() != null ? "." + re.fieldName() : ""));
            }
        }

        for (String orphan : orphans) {
            log.warn("privacy_consistency_check {}", orphan);
        }

        if (!violations.isEmpty()) {
            String message = "Data classification consistency check failed with "
                    + violations.size() + " violation(s):\n"
                    + String.join("\n", violations);
            log.error("privacy_consistency_check_failed violations={} orphans={} details={}",
                    violations.size(), orphans.size(), message);
            throw new IllegalStateException(message);
        }

        log.info("privacy_consistency_check_passed annotated={} registry={} orphans={}",
                codeElements.size(), registryElements.size(), orphans.size());
    }

    private Set<AnnotatedElement> scanAnnotatedElements() {
        Set<AnnotatedElement> elements = new HashSet<>();
        for (String basePackage : scanPackages.split(",")) {
            basePackage = basePackage.strip();
            if (basePackage.isEmpty()) continue;

            // Scan types annotated with @DataClassification at class level
            ClassPathScanningCandidateComponentProvider typeScanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            typeScanner.addIncludeFilter(new AnnotationTypeFilter(DataClassification.class));
            for (var bd : typeScanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    if (clazz.isAnnotationPresent(DataClassification.class)) {
                        elements.add(new AnnotatedElement(clazz.getSimpleName(), null));
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("privacy_scan_class_not_found class={}", bd.getBeanClassName());
                }
            }

            // Scan @Entity classes for field-level @DataClassification
            ClassPathScanningCandidateComponentProvider entityScanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            entityScanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            for (var bd : entityScanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    for (Field field : clazz.getDeclaredFields()) {
                        if (field.isAnnotationPresent(DataClassification.class)) {
                            elements.add(new AnnotatedElement(clazz.getSimpleName(), field.getName()));
                        }
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("privacy_scan_entity_not_found class={}", bd.getBeanClassName());
                }
            }
        }
        return elements;
    }

    private Set<AnnotatedElement> loadRegistryElements() {
        Set<AnnotatedElement> elements = new HashSet<>();
        for (ClassificationView view : registry.findAll()) {
            elements.add(new AnnotatedElement(view.entityName(), view.fieldName()));
        }
        return elements;
    }

    record AnnotatedElement(String entityName, String fieldName) {}
}
