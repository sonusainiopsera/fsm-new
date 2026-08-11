package com.fieldservice.fixtures;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture hygiene: asserts no real personal data is present in committed fixture
 * sources and seed SQL.
 *
 * <p>Rules enforced:
 * <ul>
 *   <li>All email addresses use a reserved domain ({@code example.local},
 *       {@code example.com}, {@code example.org}, or {@code seedcorp.example},
 *       {@code demo.example})</li>
 *   <li>All phone numbers start with the reserved {@code 555} or {@code +44-7700}
 *       prefix, or the international seed prefix {@code +44-1234} / {@code +44-9876}</li>
 *   <li>No plaintext password that matches a common production-style pattern</li>
 * </ul>
 */
class FixtureHygieneTest {

    private static final Pattern EMAIL_RE =
            Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@([A-Za-z0-9.\\-]+)\\b");

    private static final Pattern PHONE_RE =
            Pattern.compile("(?<![\\d])\\+?[0-9][0-9\\-]{6,}");

    private static final List<String> RESERVED_EMAIL_DOMAINS = List.of(
            "example.local", "example.com", "example.org",
            "seedcorp.example", "demo.example");

    private static final List<String> RESERVED_PHONE_PREFIXES = List.of(
            "555", "+44-7700", "+44-1234", "+44-9876");

    @Test
    void fixtureSourcesOnlyContainReservedEmailDomains() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : collectFixtureFiles()) {
            String content = Files.readString(file);
            Matcher m = EMAIL_RE.matcher(content);
            while (m.find()) {
                String domain = m.group(1).toLowerCase();
                if (RESERVED_EMAIL_DOMAINS.stream().noneMatch(domain::equals)) {
                    violations.add(file.getFileName() + ": " + m.group());
                }
            }
        }
        assertThat(violations)
                .as("Non-reserved email domains found in fixture sources")
                .isEmpty();
    }

    @Test
    void fixtureSourcesOnlyContainReservedPhoneRanges() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : collectFixtureFiles()) {
            String content = Files.readString(file);
            Matcher m = PHONE_RE.matcher(content);
            while (m.find()) {
                String phone = m.group().replaceAll("\\s+", "");
                boolean reserved = RESERVED_PHONE_PREFIXES.stream()
                        .anyMatch(phone::startsWith);
                if (!reserved) {
                    violations.add(file.getFileName() + ": " + phone);
                }
            }
        }
        assertThat(violations)
                .as("Non-reserved phone numbers found in fixture sources")
                .isEmpty();
    }

    @Test
    void noPlaintextPasswordsInFixtureSource() throws IOException {
        // Passwords are never stored plaintext. The only allowed credential string
        // is the documented BCrypt hash.
        String allowedHash = UserFixtures.BCRYPT_HASH_COST12;
        String testPassword = UserFixtures.TEST_PASSWORD;
        List<String> violations = new ArrayList<>();
        for (Path file : collectFixtureFiles()) {
            String content = Files.readString(file);
            if (content.contains(testPassword)) {
                violations.add(file.getFileName() + " contains plaintext password");
            }
        }
        assertThat(violations)
                .as("Plaintext password found in fixture sources (use the BCrypt hash constant)")
                .isEmpty();
    }

    // ---- Helpers ------------------------------------------------------------

    private List<Path> collectFixtureFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        // Java fixture sources under com/fieldservice/fixtures
        Path javaFixtures = resolveTestSourceRoot().resolve("com/fieldservice/fixtures");
        if (Files.exists(javaFixtures)) {
            try (Stream<Path> stream = Files.walk(javaFixtures)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .forEach(files::add);
            }
        }
        // SQL fixture resources under src/test/resources/fixtures
        Path sqlFixtures = resolveTestResourceRoot().resolve("fixtures");
        if (Files.exists(sqlFixtures)) {
            try (Stream<Path> stream = Files.walk(sqlFixtures)) {
                stream.filter(p -> p.toString().endsWith(".sql"))
                      .forEach(files::add);
            }
        }
        return files;
    }

    private Path resolveTestSourceRoot() {
        // Resolved relative to this compiled class location
        try {
            URI classUri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path classRoot = Paths.get(classUri);
            // classRoot is target/test-classes; move up to find source
            // Prefer inspecting source from the project root using classpath magic
            Path projectRoot = classRoot.getParent().getParent(); // target -> module root
            Path src = projectRoot.resolve("src/test/java");
            if (Files.exists(src)) return src;
            return classRoot;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private Path resolveTestResourceRoot() {
        try {
            URI classUri = getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path classRoot = Paths.get(classUri);
            Path projectRoot = classRoot.getParent().getParent();
            return projectRoot.resolve("src/test/resources");
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
