package com.fieldservice.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hygiene scan: asserts that no fixture source or seed SQL contains real personal data.
 *
 * <p>Checks performed:
 * <ol>
 *   <li>Email addresses use only IANA-reserved {@code example.com} or {@code example.org} domains.</li>
 *   <li>Phone numbers use only the NANP reserved {@code +15555550xxx} range or {@code +1555555xxxx}.</li>
 *   <li>No BCrypt placeholder strings (non-functional hash fragments) appear in fixture sources.</li>
 * </ol>
 *
 * <p>Finding a violation here means real personal data or placeholder credentials have been
 * committed; fix by replacing the value with a reserved-range equivalent.
 */
@DisplayName("Fixture hygiene: no real PII or placeholder credentials in fixture sources")
class FixtureHygieneTest {

    // Pattern: any @-domain that is NOT example.com or example.org
    private static final Pattern NON_RESERVED_EMAIL = Pattern.compile(
            "@(?!example\\.(?:com|org))[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    // Pattern: phone number that does NOT start with +1555555
    // Matches E.164-format numbers that start with + followed by digits, NOT in our reserved range
    private static final Pattern NON_RESERVED_PHONE = Pattern.compile(
            "(?<![a-zA-Z0-9])\\+(?!1555555)[1-9][0-9]{6,14}(?![0-9])");

    // Pattern: BCrypt placeholder strings (non-functional; contain literal "placeholder")
    private static final Pattern BCRYPT_PLACEHOLDER = Pattern.compile(
            "\\$2[ab]\\$[0-9]+\\$.*placeholder.*", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("No non-reserved email domains in fixture Java sources")
    void noNonReservedEmailDomains_javaFixtureSources() throws IOException {
        List<String> violations = new ArrayList<>();
        Path fixturesDir = resolveFixturesDir();

        try (Stream<Path> paths = Files.walk(fixturesDir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .forEach(path -> scanForPattern(path, NON_RESERVED_EMAIL, violations));
        }

        assertThat(violations)
                .as("Non-reserved email domains found in fixture sources (must use example.com or example.org)")
                .isEmpty();
    }

    @Test
    @DisplayName("No non-reserved phone numbers in fixture Java sources")
    void noNonReservedPhones_javaFixtureSources() throws IOException {
        List<String> violations = new ArrayList<>();
        Path fixturesDir = resolveFixturesDir();

        try (Stream<Path> paths = Files.walk(fixturesDir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .forEach(path -> scanForPattern(path, NON_RESERVED_PHONE, violations));
        }

        assertThat(violations)
                .as("Non-reserved phone numbers found in fixture sources (must use +15555550xxx range)")
                .isEmpty();
    }

    @Test
    @DisplayName("No BCrypt placeholder hashes in fixture SQL and Java sources")
    void noBcryptPlaceholders_fixtureSourcesAndSql() throws IOException {
        List<String> violations = new ArrayList<>();

        // Check fixture Java sources
        Path fixturesDir = resolveFixturesDir();
        try (Stream<Path> paths = Files.walk(fixturesDir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .forEach(path -> scanForPattern(path, BCRYPT_PLACEHOLDER, violations));
        }

        // Check seed SQL under src/test/resources/fixtures/
        Path sqlDir = resolveSeedSqlDir();
        if (Files.exists(sqlDir)) {
            try (Stream<Path> paths = Files.walk(sqlDir)) {
                paths.filter(p -> p.toString().endsWith(".sql"))
                     .forEach(path -> scanForPattern(path, BCRYPT_PLACEHOLDER, violations));
            }
        }

        assertThat(violations)
                .as("BCrypt placeholder hashes found (use real cost-12 hashes or computed test values)")
                .isEmpty();
    }

    @Test
    @DisplayName("No non-reserved email domains in seed SQL")
    void noNonReservedEmailDomains_seedSql() throws IOException {
        List<String> violations = new ArrayList<>();
        Path sqlDir = resolveSeedSqlDir();

        if (Files.exists(sqlDir)) {
            try (Stream<Path> paths = Files.walk(sqlDir)) {
                paths.filter(p -> p.toString().endsWith(".sql"))
                     .forEach(path -> scanForPattern(path, NON_RESERVED_EMAIL, violations));
            }
        }

        assertThat(violations)
                .as("Non-reserved email domains found in seed SQL (must use example.com or example.org)")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void scanForPattern(Path path, Pattern pattern, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                var matcher = pattern.matcher(line);
                if (matcher.find()) {
                    violations.add(path.getFileName() + ":" + (i + 1) + " → " + line.strip());
                }
            }
        } catch (IOException e) {
            violations.add("UNREADABLE: " + path + " → " + e.getMessage());
        }
    }

    private Path resolveFixturesDir() {
        // Resolve relative to classpath root which resolves to target/test-classes at runtime;
        // walk back up to find src/test/java/com/fieldservice/fixtures
        Path classesDir = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        Path srcTestJava = classesDir.getParent().getParent()
                .resolve("src/test/java/com/fieldservice/fixtures");
        if (Files.exists(srcTestJava)) return srcTestJava;
        // Fallback: module root
        return classesDir.getParent().getParent()
                .resolve("src/test/java");
    }

    private Path resolveSeedSqlDir() {
        Path classesDir = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().getPath());
        return classesDir.getParent().getParent()
                .resolve("src/test/resources/fixtures");
    }
}
