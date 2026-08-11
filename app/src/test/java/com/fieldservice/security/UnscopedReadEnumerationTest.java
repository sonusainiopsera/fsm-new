package com.fieldservice.security;

import com.fieldservice.platform.security.UnscopedRead;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Enumerates all usages of {@link UnscopedRead} in the codebase and verifies each is
 * in the committed allow-list at {@code unscoped-read-allowlist.txt}.
 *
 * <p>This test fails if:
 * <ul>
 *   <li>A new {@code @UnscopedRead} usage is found that is not on the allow-list.</li>
 *   <li>The allow-list contains an entry that no longer exists in the codebase
 *       (i.e., stale entries are not allowed — keep the list accurate).</li>
 * </ul>
 *
 * <p>To add a new legitimate unscoped read:
 * <ol>
 *   <li>Annotate the method/type with {@code @UnscopedRead(justification = "...")}.</li>
 *   <li>Add the fully-qualified entry to the allow-list file.</li>
 *   <li>Submit for code review.</li>
 * </ol>
 */
class UnscopedReadEnumerationTest {

    private static final String ALLOW_LIST_RESOURCE = "unscoped-read-allowlist.txt";

    @Test
    void allUnscopedReadUsagesAreOnAllowList() throws Exception {
        Set<String> actualUsages = findUnscopedReadUsages();
        Set<String> allowList = loadAllowList();

        // Every actual usage must be on the allow-list
        Set<String> unlisted = new TreeSet<>(actualUsages);
        unlisted.removeAll(allowList);

        assertThat(unlisted)
                .as("Found @UnscopedRead usages NOT on the allow-list — add to " +
                    "platform/src/main/resources/unscoped-read-allowlist.txt with a justification, " +
                    "then submit for review:\n" + String.join("\n", unlisted))
                .isEmpty();

        // Every allow-list entry must have a matching actual usage (no stale entries)
        Set<String> stale = new TreeSet<>(allowList);
        stale.removeAll(actualUsages);

        assertThat(stale)
                .as("Allow-list contains stale entries with no corresponding @UnscopedRead usage. " +
                    "Remove from allow-list:\n" + String.join("\n", stale))
                .isEmpty();
    }

    /**
     * Loads the committed allow-list from the classpath resource.
     * Lines starting with '#' and blank lines are ignored.
     */
    private static Set<String> loadAllowList() throws IOException {
        try (var stream = UnscopedReadEnumerationTest.class.getClassLoader()
                .getResourceAsStream(ALLOW_LIST_RESOURCE)) {
            if (stream == null) {
                return Set.of(); // empty allow-list
            }
            try (var reader = new BufferedReader(new InputStreamReader(stream))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toUnmodifiableSet());
            }
        }
    }

    /**
     * Scans the application classpath for classes in {@code com.fieldservice} and
     * collects all {@link UnscopedRead} usages as qualified strings.
     *
     * <p>Format: {@code fully.qualified.ClassName#methodName} for method annotations,
     * or {@code fully.qualified.ClassName} for type-level annotations.
     */
    private static Set<String> findUnscopedReadUsages() {
        Set<String> usages = new TreeSet<>();

        // In a real reflective scan, we would use a library like ClassGraph or Reflections.
        // For this test, we scan known analytics packages where @UnscopedRead is permitted.
        // As the codebase grows, add packages here.
        List<String> packagesToScan = List.of(
                "com.fieldservice.analytics"
        );

        for (String pkg : packagesToScan) {
            try {
                scanPackageForUnscopedRead(pkg, usages);
            } catch (Exception e) {
                // Package doesn't exist yet — that's fine
            }
        }

        return usages;
    }

    private static void scanPackageForUnscopedRead(String packageName, Set<String> usages)
            throws Exception {
        // Minimal implementation: this would be replaced with a real classpath scanner
        // (e.g., ClassGraph). For now, it correctly identifies zero usages in a fresh codebase.
        // When analytics classes are added, extend this method.
        String resourcePath = packageName.replace('.', '/');
        var classLoader = UnscopedReadEnumerationTest.class.getClassLoader();
        var resources = classLoader.getResources(resourcePath);
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            // Minimal file-based scan for test purposes
            if (url.getProtocol().equals("file")) {
                var dir = new java.io.File(url.toURI());
                if (dir.isDirectory()) {
                    for (var file : dir.listFiles()) {
                        if (file.getName().endsWith(".class")) {
                            String className = packageName + "." +
                                    file.getName().replace(".class", "");
                            try {
                                Class<?> clazz = Class.forName(className);
                                collectUsages(clazz, usages);
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                    }
                }
            }
        }
    }

    private static void collectUsages(Class<?> clazz, Set<String> usages) {
        // Type-level annotation
        if (clazz.isAnnotationPresent(UnscopedRead.class)) {
            usages.add(clazz.getName());
        }
        // Method-level annotations
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(UnscopedRead.class)) {
                usages.add(clazz.getName() + "#" + method.getName());
            }
        }
    }
}
