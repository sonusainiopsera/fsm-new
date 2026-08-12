package com.fieldservice.app.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CI fixture scanner — fails the build if any committed test resource outside the
 * approved synthetic allow-list contains a value matching a personal-data pattern.
 *
 * <p>Patterns checked:
 * <ul>
 *   <li>Email addresses</li>
 *   <li>UK/international phone numbers</li>
 *   <li>UK postcodes</li>
 *   <li>High-precision coordinate pairs (lat,lon)</li>
 * </ul>
 *
 * <p>Approved files are listed in {@code src/test/resources/fixtures/pii-allow-list.txt}.
 *
 * <p>To add a new approved file: add it to the allow-list with a justification comment,
 * verified it contains only synthetic/fictional data, and get sign-off from the privacy team.
 * See TESTING.md §Fixture-Scanner for the full process.
 */
class FixturePiiScannerTest {

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private static final Pattern PHONE =
            Pattern.compile("(?<![\\w.])(?:\\+?\\d[\\s\\-.]?){7,14}\\d(?![\\w.])");

    private static final Pattern UK_POSTCODE =
            Pattern.compile("\\b[A-Z]{1,2}\\d[\\dA-Z]?\\s*\\d[A-Z]{2}\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern COORDINATE_PAIR =
            Pattern.compile("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}");

    private static final List<Pattern> PII_PATTERNS =
            List.of(EMAIL, PHONE, UK_POSTCODE, COORDINATE_PAIR);

    private static final String FIXTURES_RESOURCE = "fixtures";
    private static final String ALLOW_LIST_RESOURCE = "fixtures/pii-allow-list.txt";

    /**
     * Proves the scanner fires on the deliberately-unsafe fixture.
     * This test MUST see the unsafe fixture as a match — if it doesn't, the scanner is broken.
     */
    @Test
    @DisplayName("Scanner detects PII in the deliberately-unsafe fixture")
    void scanner_detects_pii_in_unsafe_fixture() throws Exception {
        Path unsafePath = resourcePath("fixtures/unsafe-pii.sql");
        List<String> hits = scanFile(unsafePath);
        assertThat(hits)
                .as("unsafe-pii.sql must contain at least one PII pattern to prove the scanner works")
                .isNotEmpty();
    }

    /**
     * Scans all fixture files outside the allow-list and asserts none contain PII.
     */
    @Test
    @DisplayName("No non-allow-listed fixture file contains a PII pattern")
    void no_non_allow_listed_fixture_contains_pii() throws Exception {
        List<String> allowedRelPaths = loadAllowList();
        Path fixturesDir = resourcePath(FIXTURES_RESOURCE);

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(fixturesDir)) {
            for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                String relPath = "fixtures/" + fixturesDir.relativize(file).toString().replace('\\', '/');
                if (allowedRelPaths.contains(relPath)) {
                    continue; // approved synthetic file
                }
                List<String> hits = scanFile(file);
                if (!hits.isEmpty()) {
                    violations.add("[" + relPath + "] found patterns: " + hits);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Fixture scanner found PII patterns in non-allow-listed files:\n"
                    + String.join("\n", violations)
                    + "\n\nTo resolve: either remove the personal data (preferred) "
                    + "or add the file to fixtures/pii-allow-list.txt with a justification. "
                    + "See TESTING.md §Fixture-Scanner.");
        }
    }

    // ---- helpers -------------------------------------------------------------

    private List<String> scanFile(Path file) throws IOException {
        String content = Files.readString(file);
        List<String> matches = new ArrayList<>();
        for (Pattern p : PII_PATTERNS) {
            var matcher = p.matcher(content);
            while (matcher.find()) {
                matches.add(p.pattern() + " → [" + matcher.group() + "]");
                break; // report first match per pattern per file — no need to list all
            }
        }
        return matches;
    }

    private List<String> loadAllowList() throws IOException {
        URL url = getClass().getClassLoader().getResource(ALLOW_LIST_RESOURCE);
        if (url == null) return Collections.emptyList();
        return Files.readAllLines(Path.of(toURI(url))).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.toList());
    }

    private Path resourcePath(String name) throws IOException {
        URL url = getClass().getClassLoader().getResource(name);
        if (url == null) {
            throw new IOException("Test resource not found: " + name);
        }
        return Path.of(toURI(url));
    }

    private static URI toURI(URL url) {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Cannot convert URL to URI: " + url, e);
        }
    }
}
