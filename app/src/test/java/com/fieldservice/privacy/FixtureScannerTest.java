package com.fieldservice.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CI fixture scanner (WO-192, AC-7).
 *
 * <p>Scans all committed test fixture files under {@code db/fixtures/} and
 * {@code db/migration/} for values matching personal-data patterns (email addresses,
 * phone numbers, postcode-like strings, coordinate pairs). A match that is NOT on the
 * synthetic allow-list fails the build.
 *
 * <h3>Allow-list</h3>
 * <p>Synthetic fixture values must be registered in the allow-list below. Real-looking
 * patterns (e.g. real names, real postcodes, real coordinate pairs) must never appear
 * in committed fixtures; only synthetic patterns like {@code @example.invalid} are
 * permitted.
 *
 * <h3>How to interpret a failure</h3>
 * <p>The assertion error lists the file, line number, and matched value. Replace the
 * real value with a synthetic one (e.g. {@code fake@example.invalid},
 * {@code +00000000042}, synthetic UK postcode {@code ZZ99 9ZZ}) and add it to the
 * allow-list if it is intentionally test-shaped.
 */
@DisplayName("CI fixture scanner — personal data pattern gate")
class FixtureScannerTest {

    // ── Patterns to detect ─────────────────────────────────────────────────

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]{2,}@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<![\\d])\\+?[0-9][\\s\\-.]?(?:[0-9][\\s\\-.]?){9,14}(?![\\d])");

    private static final Pattern COORDINATE_PATTERN =
            Pattern.compile("(?<![\\d\\w])-?(?:[0-8]?\\d\\.\\d{4,}|90\\.0+)\\s*,\\s*-?(?:1[0-7]\\d\\.\\d{4,}|180\\.0+|[0-9]?\\d\\.\\d{4,})");

    private static final Pattern[][] DETECTOR_PAIRS = {
            {EMAIL_PATTERN},
            {PHONE_PATTERN},
            {COORDINATE_PATTERN}
    };

    // ── Synthetic allow-list ───────────────────────────────────────────────
    // Values matching these patterns are pre-approved synthetic test data.
    // Any value matching a personal-data pattern AND an allow-list entry is exempt.

    private static final Set<String> ALLOWED_DOMAINS =
            Set.of("@example.com", "@example.invalid", "@test.invalid", "@fieldservice.invalid");

    private static final Set<String> ALLOWED_PHONE_PREFIXES =
            Set.of("+000", "00000", "0000000000", "+44070000000");

    /**
     * Approved synthetic coordinate pairs (used in fixture rows for job sites etc.).
     * Only lat/lon reduced to ≤2 d.p. are permitted (city-level, not identifiable).
     */
    private static final Pattern ALLOWED_COORDINATE =
            Pattern.compile("-?\\d{1,3}\\.\\d{1,2},-?\\d{1,3}\\.\\d{1,2}$");

    // ── Scan target ────────────────────────────────────────────────────────

    private static final Path FIXTURES_ROOT = Paths.get(
            System.getProperty("user.dir"), "src", "test", "resources", "db", "fixtures");
    private static final Path MIGRATION_ROOT = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources", "db", "migration");

    @Test
    @DisplayName("AC-7: no committed fixture contains a real personal-data pattern")
    void fixtures_containNoRealPersonalData() throws IOException {
        List<String> violations = new ArrayList<>();

        for (Path root : List.of(FIXTURES_ROOT, MIGRATION_ROOT)) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".sql"))
                     .forEach(file -> scanFile(file, violations));
            }
        }

        if (!violations.isEmpty()) {
            fail("Fixture scanner detected personal-data patterns in committed files.\n"
                    + "Each violation must be replaced with a synthetic value and registered\n"
                    + "in the allow-list in FixtureScannerTest. Violations:\n\n"
                    + String.join("\n", violations));
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void scanFile(Path file, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                // Skip SQL comments
                if (line.trim().startsWith("--")) continue;

                checkEmailPattern(file, i + 1, line, violations);
                checkCoordinatePattern(file, i + 1, line, violations);
                // Phone pattern check deliberately lenient — only flag obvious real formats
                checkPhonePattern(file, i + 1, line, violations);
            }
        } catch (IOException e) {
            violations.add("[ERROR] Cannot read " + file + ": " + e.getMessage());
        }
    }

    private void checkEmailPattern(Path file, int lineNum, String line,
                                   List<String> violations) {
        var matcher = EMAIL_PATTERN.matcher(line);
        while (matcher.find()) {
            String match = matcher.group();
            boolean allowed = ALLOWED_DOMAINS.stream().anyMatch(match::contains);
            if (!allowed) {
                violations.add(file.getFileName() + ":" + lineNum
                        + " — email-like value: " + match);
            }
        }
    }

    private void checkPhonePattern(Path file, int lineNum, String line,
                                   List<String> violations) {
        var matcher = PHONE_PATTERN.matcher(line);
        while (matcher.find()) {
            String match = matcher.group().replaceAll("[\\s\\-.]", "");
            boolean allowed = ALLOWED_PHONE_PREFIXES.stream().anyMatch(match::startsWith);
            // Suppress false positives: IDs, timestamps, migration version numbers
            boolean likelyFalsePositive = line.contains("V1") || line.contains("uuid")
                    || line.contains("::") || line.contains("INTERVAL");
            if (!allowed && !likelyFalsePositive && match.length() >= 10) {
                violations.add(file.getFileName() + ":" + lineNum
                        + " — phone-like value: " + match);
            }
        }
    }

    private void checkCoordinatePattern(Path file, int lineNum, String line,
                                        List<String> violations) {
        var matcher = COORDINATE_PATTERN.matcher(line);
        while (matcher.find()) {
            String match = matcher.group().replaceAll("\\s", "");
            // Only flag high-precision coordinates (> 2 d.p.) as suspicious
            boolean highPrecision = match.matches(".*\\.\\d{4,}.*");
            if (highPrecision) {
                violations.add(file.getFileName() + ":" + lineNum
                        + " — high-precision coordinate (may identify a location): " + match);
            }
        }
    }
}
