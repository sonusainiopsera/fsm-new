package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.DataClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup consistency check that compares code annotations against registry rows.
 *
 * <p>Fails fast with a single aggregated {@link IllegalStateException} listing:
 * <ul>
 *   <li><b>MISSING</b>: annotated elements with no corresponding registry row</li>
 *   <li><b>ORPHANED</b>: registry rows whose entity/field no longer has an annotation</li>
 * </ul>
 *
 * <p>Disabled in the {@code test} profile to avoid Testcontainers dependency on a fully
 * seeded registry. Run via {@code mvn -pl app verify} or on application startup in all
 * other profiles.
 *
 * <p>Annotation scanning is restricted to {@code app.privacy.base-packages} (default
 * {@code com.fieldservice}) to avoid reflective classpath scanning beyond the platform.
 */
@Component
@Profile("!test")
class ClassificationConsistencyCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClassificationConsistencyCheck.class);

    @Value("${app.privacy.base-packages:com.fieldservice}")
    private String basePackage;

    private final ClassificationRegistry registry;

    ClassificationConsistencyCheck(ClassificationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("privacy.consistency-check: scanning {} for @DataClassification annotations", basePackage);

        Set<String> annotatedKeys = collectAnnotatedKeys();
        Set<String> registryKeys = registry.findAll().stream()
                .map(v -> registryKey(v.entityName(), v.fieldName()))
                .collect(Collectors.toSet());

        List<String> missing = annotatedKeys.stream()
                .filter(k -> !registryKeys.contains(k))
                .sorted()
                .toList();

        List<String> orphaned = registryKeys.stream()
                .filter(k -> !annotatedKeys.contains(k))
                .sorted()
                .toList();

        if (missing.isEmpty() && orphaned.isEmpty()) {
            log.info("privacy.consistency-check: OK — {} annotated elements, {} registry rows",
                    annotatedKeys.size(), registryKeys.size());
            return;
        }

        StringBuilder sb = new StringBuilder("Data classification drift detected:\n");
        if (!missing.isEmpty()) {
            sb.append("  MISSING registry rows (annotated in code, absent from data_classification):\n");
            missing.forEach(k -> sb.append("    - ").append(k).append("\n"));
        }
        if (!orphaned.isEmpty()) {
            sb.append("  ORPHANED registry rows (in data_classification but no @DataClassification found):\n");
            orphaned.forEach(k -> sb.append("    - ").append(k).append("\n"));
        }
        sb.append("To fix: run the drift repair procedure in TESTING.md#privacy-runbook");

        throw new IllegalStateException(sb.toString());
    }

    private Set<String> collectAnnotatedKeys() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(DataClassification.class));

        Set<String> keys = new HashSet<>();

        for (var bd : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> clazz = Class.forName(Objects.requireNonNull(bd.getBeanClassName()));

                DataClassification typeAnnotation = AnnotationUtils.findAnnotation(clazz, DataClassification.class);
                if (typeAnnotation != null) {
                    keys.add(registryKey(clazz.getSimpleName(), null));
                }

                for (Field field : clazz.getDeclaredFields()) {
                    DataClassification fieldAnnotation = AnnotationUtils.getAnnotation(field, DataClassification.class);
                    if (fieldAnnotation != null) {
                        keys.add(registryKey(clazz.getSimpleName(), field.getName()));
                    }
                }
            } catch (ClassNotFoundException e) {
                log.warn("privacy.consistency-check: could not load class {}", bd.getBeanClassName(), e);
            }
        }

        return keys;
    }

    private static String registryKey(String entityName, String fieldName) {
        return fieldName == null ? entityName : entityName + "." + fieldName;
    }
}
