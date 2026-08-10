package com.fieldservice.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Reflective test that enumerates every {@link UnscopedRead} annotation usage in the
 * compiled classpath and asserts it appears in the committed allow-list file.
 *
 * <p>If a new {@code @UnscopedRead} is added without updating the allow-list, this
 * test fails — preventing the silent growth of the opt-out surface.
 */
class UnscopedReadAllowListTest {

    private static final String ALLOWLIST_RESOURCE = "unscoped-read-allowlist.txt";

    @Test
    void all_unscoped_read_usages_are_in_allowlist() throws Exception {
        Set<String> allowList = loadAllowList();
        Set<String> violations = new HashSet<>();

        // Scan the compiled classes on the classpath
        // In this test, we walk the domain package
        String[] packagesToScan = {
            "com.fieldservice.domain",
            "com.fieldservice.app",
            "com.fieldservice.platform"
        };

        for (String pkg : packagesToScan) {
            scanPackage(pkg, allowList, violations);
        }

        assertThat(violations)
                .as("@UnscopedRead usages not in the allow-list.\n"
                        + "Add entries to platform/src/main/resources/unscoped-read-allowlist.txt "
                        + "with a non-trivial justification and obtain a code-review approval.\n"
                        + "Violations found")
                .isEmpty();
    }

    private void scanPackage(String packageName, Set<String> allowList, Set<String> violations) {
        try {
            // Use the test classloader to find classes in the package
            var loader = Thread.currentThread().getContextClassLoader();
            var path = packageName.replace('.', '/');
            var resources = loader.getResources(path);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    scanDirectory(new java.io.File(url.getFile()), packageName, allowList, violations);
                }
            }
        } catch (IOException e) {
            // Package not on classpath — skip
        }
    }

    private void scanDirectory(java.io.File dir, String packageName,
                                Set<String> allowList, Set<String> violations) {
        if (!dir.exists()) return;
        for (java.io.File file : dir.listFiles()) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(), allowList, violations);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().replace(".class", "");
                checkClass(className, allowList, violations);
            }
        }
    }

    private void checkClass(String className, Set<String> allowList, Set<String> violations) {
        try {
            Class<?> cls = Class.forName(className, false,
                    Thread.currentThread().getContextClassLoader());

            // Check type-level annotation
            if (cls.isAnnotationPresent(UnscopedRead.class)) {
                if (!allowList.contains(className)) {
                    violations.add("Type: " + className);
                }
            }

            // Check method-level annotations
            for (Method method : cls.getDeclaredMethods()) {
                if (method.isAnnotationPresent(UnscopedRead.class)) {
                    String key = className + "#" + method.getName();
                    if (!allowList.contains(key)) {
                        violations.add("Method: " + key);
                    }
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError | ExceptionInInitializerError e) {
            // Ignore — class may not be loadable in this context
        }
    }

    private Set<String> loadAllowList() throws IOException {
        Set<String> entries = new HashSet<>();
        ClassPathResource resource = new ClassPathResource(ALLOWLIST_RESOURCE);
        if (!resource.exists()) {
            fail("Allow-list file not found at classpath:" + ALLOWLIST_RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream()))) {
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(entries::add);
        }
        return entries;
    }
}
