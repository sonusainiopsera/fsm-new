package com.fieldservice.api.openapi;

import com.fieldservice.security.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI snapshot comparison test.
 *
 * <p>Retrieves the live /api-docs document, normalises it (strips volatile fields such as
 * server URL, build version; sorts object keys), and compares the result byte-for-byte with
 * the committed snapshot at {@code src/test/resources/openapi/snapshot.json}.
 *
 * <p>Any undeclared contract change — added/removed path, changed response schema, new
 * component — will cause this test to fail with a readable diff and regeneration instructions.
 *
 * <h2>Regenerating the snapshot</h2>
 * <pre>
 *   mvn test -pl app -Dtest=OpenApiSnapshotTest -DUPDATE_SNAPSHOT=true
 * </pre>
 * Then commit the updated {@code src/test/resources/openapi/snapshot.json}.
 *
 * <h2>Bootstrap (first run)</h2>
 * If the snapshot file contains only {@code {}} (the bootstrap placeholder), this test
 * auto-generates the snapshot and passes. Commit the generated file and re-run.
 *
 * <h2>Build artifact</h2>
 * Every run writes the raw (un-normalised) spec to {@code target/openapi/field-service-api.json}
 * for downstream typed client generation.
 */
class OpenApiSnapshotTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSnapshotTest.class);

    private static final String SNAPSHOT_CLASSPATH = "/openapi/snapshot.json";
    private static final String SNAPSHOT_SOURCE_PATH = "src/test/resources/openapi/snapshot.json";

    @Autowired
    MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // AC5, AC6 — Snapshot comparison and build artifact publication
    // -------------------------------------------------------------------------

    @Test
    void snapshotMatchesCommittedSpec() throws Exception {
        String rawJson = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Always publish the raw spec to target/ as a versioned build artifact
        publishBuildArtifact(rawJson);

        String actual = OpenApiNormalizer.normalize(rawJson);

        if (System.getProperty("UPDATE_SNAPSHOT") != null) {
            writeSnapshot(actual);
            log.info("Snapshot updated at {}. Commit the file.", SNAPSHOT_SOURCE_PATH);
            return;
        }

        String committed = readCommittedSnapshot();

        if (committed == null) {
            // No classpath resource found — bootstrap
            writeSnapshot(actual);
            fail("Snapshot file not found. Generated at " + SNAPSHOT_SOURCE_PATH +
                    ". Commit the file and re-run.\n" +
                    "Regenerate: mvn test -pl app -Dtest=OpenApiSnapshotTest -DUPDATE_SNAPSHOT=true");
        }

        if (committed.trim().equals("{}")) {
            // Bootstrap placeholder — write real snapshot and pass (first run)
            writeSnapshot(actual);
            log.warn("Bootstrap placeholder replaced with real snapshot at {}. " +
                    "Commit the file and re-run to verify.", SNAPSHOT_SOURCE_PATH);
            return;
        }

        if (!actual.equals(committed)) {
            fail("OpenAPI snapshot mismatch — undeclared contract change detected.\n" +
                    "Regenerate: mvn test -pl app -Dtest=OpenApiSnapshotTest -DUPDATE_SNAPSHOT=true\n" +
                    "Then commit the updated snapshot.\n\n" +
                    diff(committed, actual));
        }
    }

    // -------------------------------------------------------------------------
    // AC7 — Normaliser is deterministic (drift detection sanity check)
    // -------------------------------------------------------------------------

    @Test
    void normalisationIsDeterministic() throws Exception {
        String rawJson = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String first  = OpenApiNormalizer.normalize(rawJson);
        String second = OpenApiNormalizer.normalize(rawJson);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void mutationIsDetectedByNormaliser() throws Exception {
        String rawJson = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String normalized = OpenApiNormalizer.normalize(rawJson);

        // Inject a synthetic path to simulate an undeclared endpoint being added
        String mutated = rawJson.replace(
                "\"paths\":{",
                "\"paths\":{\"/_drift_probe\":{\"get\":{\"operationId\":\"driftProbe\"," +
                        "\"responses\":{\"200\":{\"description\":\"ok\"}}}},"
        );
        // Verify the mutation wasn't a no-op (i.e., the replacement actually happened)
        if (mutated.equals(rawJson)) {
            // paths might be empty — inject differently
            mutated = rawJson.replace("\"paths\":{}",
                    "\"paths\":{\"/_drift_probe\":{\"get\":{\"operationId\":\"driftProbe\"," +
                            "\"responses\":{\"200\":{\"description\":\"ok\"}}}}}");
        }
        String normalizedMutated = OpenApiNormalizer.normalize(mutated);

        assertThat(normalizedMutated)
                .as("A mutated spec (added path) must produce a different normalised output, " +
                        "proving drift would be detected by the snapshot comparison")
                .isNotEqualTo(normalized);
    }

    // -------------------------------------------------------------------------
    // AC9 — Unit tests for normaliser (volatile field stripping, key ordering)
    // -------------------------------------------------------------------------

    @Test
    void normaliserStripsVersionAndServerUrl() {
        String input = "{\"info\":{\"version\":\"1.2.3\",\"title\":\"Test API\"}," +
                "\"servers\":[{\"url\":\"https://api.example.com\",\"description\":\"prod\"}]," +
                "\"openapi\":\"3.0.1\"}";

        String result = OpenApiNormalizer.normalize(input);

        assertThat(result).contains("\"version\" : \"SNAPSHOT\"");
        assertThat(result).contains("\"url\" : \"PLACEHOLDER\"");
        assertThat(result).doesNotContain("1.2.3");
        assertThat(result).doesNotContain("api.example.com");
    }

    @Test
    void normaliserSortsObjectKeysDeterministically() {
        String inputZ = "{\"z\":1,\"a\":2,\"m\":3}";
        String inputA = "{\"a\":2,\"m\":3,\"z\":1}";

        String normalizedZ = OpenApiNormalizer.normalize(inputZ);
        String normalizedA = OpenApiNormalizer.normalize(inputA);

        assertThat(normalizedZ).isEqualTo(normalizedA);
        // Keys must appear in alphabetical order
        int posA = normalizedZ.indexOf("\"a\"");
        int posM = normalizedZ.indexOf("\"m\"");
        int posZ = normalizedZ.indexOf("\"z\"");
        assertThat(posA).isLessThan(posM);
        assertThat(posM).isLessThan(posZ);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void publishBuildArtifact(String rawJson) {
        try {
            Path targetDir = Paths.get("target", "openapi");
            Files.createDirectories(targetDir);
            Files.writeString(targetDir.resolve("field-service-api.json"), rawJson);
            log.info("Published OpenAPI spec to {}", targetDir.resolve("field-service-api.json"));
        } catch (Exception e) {
            log.warn("Could not write build artifact: {}", e.getMessage());
        }
    }

    private void writeSnapshot(String normalizedJson) {
        try {
            Path sourcePath = Paths.get(SNAPSHOT_SOURCE_PATH);
            Files.createDirectories(sourcePath.getParent());
            Files.writeString(sourcePath, normalizedJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write snapshot to " + SNAPSHOT_SOURCE_PATH, e);
        }
    }

    private String readCommittedSnapshot() {
        try (InputStream is = getClass().getResourceAsStream(SNAPSHOT_CLASSPATH)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String diff(String expected, String actual) {
        String[] expectedLines = expected.split("\n");
        String[] actualLines   = actual.split("\n");

        StringBuilder sb = new StringBuilder("--- expected\n+++ actual\n");
        int maxLines = Math.max(expectedLines.length, actualLines.length);
        int diffCount = 0;

        for (int i = 0; i < maxLines && diffCount < 40; i++) {
            String exp = i < expectedLines.length ? expectedLines[i] : "<missing>";
            String act = i < actualLines.length   ? actualLines[i]   : "<missing>";
            if (!exp.equals(act)) {
                sb.append("Line ").append(i + 1).append(":\n");
                sb.append("  - ").append(exp).append("\n");
                sb.append("  + ").append(act).append("\n");
                diffCount++;
            }
        }
        if (diffCount == 40) sb.append("... (truncated after 40 differences)\n");
        return sb.toString();
    }
}
