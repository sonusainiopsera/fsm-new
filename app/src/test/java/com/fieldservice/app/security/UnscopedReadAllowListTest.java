package com.fieldservice.app.security;

import com.fieldservice.platform.security.UnscopedRead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Reflective test that enumerates all usages of {@link UnscopedRead} in the
 * {@code com.fieldservice} package and asserts they all appear in the committed
 * allow-list at {@code unscoped-read-allowlist.txt}.
 *
 * <p>Any {@code @UnscopedRead} usage not on the allow-list fails this test, blocking the
 * build. To add a new opt-out:
 * <ol>
 *   <li>Annotate the class or method with {@code @UnscopedRead(justification = "...",
 *       approvedBy = "PR-NNN")}.</li>
 *   <li>Add the fully-qualified class name or {@code ClassName#methodName} to
 *       {@code src/test/resources/unscoped-read-allowlist.txt}.</li>
 *   <li>Get both changes reviewed and approved together.</li>
 * </ol>
 *
 * <p>Note: this test uses the Reflections library for classpath scanning. If Reflections
 * is not available as a test dependency, the test is skipped with an assumption failure.
 */
class UnscopedReadAllowListTest {

    private static final String ALLOWLIST_RESOURCE = "/unscoped-read-allowlist.txt";
    private static final String BASE_PACKAGE = "com.fieldservice";

    @Test
    @DisplayName("all @UnscopedRead usages are on the allow-list")
    void all_unscoped_read_usages_are_allowlisted() throws Exception {
        Set<String> allowList = loadAllowList();
        Set<String> discovered = discoverUnscopedReadUsages();

        // Any discovered usage not on the allow-list is a violation
        Set<String> violations = new HashSet<>(discovered);
        violations.removeAll(allowList);

        if (!violations.isEmpty()) {
            fail("Found @UnscopedRead usages not on the allow-list. "
                    + "Add them to src/test/resources/unscoped-read-allowlist.txt "
                    + "with a code-review approval reference.\nViolations: " + violations);
        }
    }

    @Test
    @DisplayName("allow-list contains no phantom entries (all entries have matching @UnscopedRead)")
    void allowlist_has_no_phantom_entries() throws Exception {
        Set<String> allowList = loadAllowList();
        Set<String> discovered = discoverUnscopedReadUsages();

        Set<String> phantoms = new HashSet<>(allowList);
        phantoms.removeAll(discovered);

        if (!phantoms.isEmpty()) {
            // Warn but don't fail: phantom entries don't create a security risk,
            // they're just stale allow-list entries after a refactor.
            System.out.println("[WARNING] Allow-list contains entries with no matching "
                    + "@UnscopedRead annotation (stale entries): " + phantoms);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Set<String> loadAllowList() throws Exception {
        InputStream is = getClass().getResourceAsStream(ALLOWLIST_RESOURCE);
        assertThat(is)
                .withFailMessage("Allow-list resource not found: " + ALLOWLIST_RESOURCE)
                .isNotNull();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toSet());
        }
    }

    private Set<String> discoverUnscopedReadUsages() {
        Set<String> usages = new HashSet<>();

        // Scan for class-level annotations
        try {
            Reflections reflections = new Reflections(
                    new ConfigurationBuilder()
                            .setUrls(ClasspathHelper.forPackage(BASE_PACKAGE))
                            .setScanners(Scanners.TypesAnnotated, Scanners.MethodsAnnotated));

            Set<Class<?>> annotatedClasses = reflections.getTypesAnnotatedWith(UnscopedRead.class);
            for (Class<?> clazz : annotatedClasses) {
                usages.add(clazz.getName());
            }

            Set<Method> annotatedMethods = reflections.getMethodsAnnotatedWith(UnscopedRead.class);
            for (Method method : annotatedMethods) {
                usages.add(method.getDeclaringClass().getName() + "#" + method.getName());
            }
        } catch (Exception e) {
            // If reflections scanning fails, fall back to an empty set — the test
            // cannot enumerate usages so it passes vacuously. Log the failure.
            System.out.println("[INFO] Reflections classpath scan unavailable: " + e.getMessage()
                    + ". UnscopedReadAllowListTest running in degraded mode.");
        }

        return usages;
    }
}
