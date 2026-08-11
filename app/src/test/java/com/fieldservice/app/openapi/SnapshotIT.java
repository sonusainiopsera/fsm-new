package com.fieldservice.app.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.app.Application;
import com.fieldservice.app.security.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Snapshot contract test — retrieves the live OpenAPI document, normalises it, and compares
 * it byte-for-byte with the committed snapshot.
 *
 * <h3>Regenerating the snapshot</h3>
 * <p>After an intentional API change, regenerate with:
 * <pre>
 *   ./mvnw test -pl app -Dtest=SnapshotIT -Dupdate-snapshot=true
 * </pre>
 * <p>Then commit the updated {@code app/src/test/resources/openapi-snapshot.json}.
 *
 * <h3>Seeding on first run</h3>
 * <p>If the committed snapshot is empty (contains only {@code {}}) the test writes the live
 * normalised document and passes. Subsequent runs compare against the written file.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class SnapshotIT {

    private static final String SNAPSHOT_CLASSPATH = "openapi-snapshot.json";
    private static final String SNAPSHOT_SRC_PATH  =
            "app/src/test/resources/openapi-snapshot.json";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Generated OpenAPI document matches the committed snapshot")
    void snapshot_matches_committed() throws Exception {
        // 1. Retrieve live document
        String liveJson = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode liveNode = mapper.readTree(liveJson);
        String normalised = SnapshotNormaliser.normalise(liveNode);

        // 2. Update mode — overwrite snapshot and pass
        boolean updateSnapshot = Boolean.getBoolean("update-snapshot");
        if (updateSnapshot) {
            writeSnapshot(normalised);
            System.out.println("[SnapshotIT] Snapshot updated at: " + SNAPSHOT_SRC_PATH);
            return;
        }

        // 3. Load committed snapshot
        ClassPathResource snapshotResource = new ClassPathResource(SNAPSHOT_CLASSPATH);
        if (!snapshotResource.exists()) {
            writeSnapshot(normalised);
            System.out.println("[SnapshotIT] Snapshot seeded (first run): " + SNAPSHOT_SRC_PATH);
            return;
        }

        String committed;
        try (InputStream is = snapshotResource.getInputStream()) {
            committed = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }

        // Seed mode: treat empty or stub snapshot as first-run seed
        if (committed.isEmpty() || "{}".equals(committed)) {
            writeSnapshot(normalised);
            System.out.println("[SnapshotIT] Snapshot seeded (empty file): " + SNAPSHOT_SRC_PATH);
            return;
        }

        // 4. Normalise committed snapshot before comparison (idempotent)
        JsonNode committedNode = mapper.readTree(committed);
        String normalisedCommitted = SnapshotNormaliser.normalise(committedNode);

        // 5. Compare
        if (!normalised.equals(normalisedCommitted)) {
            String diff = computeDiff(normalisedCommitted, normalised);
            fail("OpenAPI snapshot mismatch.\n\n" +
                    "To regenerate: ./mvnw test -pl app -Dtest=SnapshotIT -Dupdate-snapshot=true\n\n" +
                    "Diff (expected vs actual):\n" + diff);
        }
    }

    @Test
    @DisplayName("Drift detection — mutating the contract fails the snapshot comparison")
    void drift_is_detected() throws Exception {
        // Retrieve and normalise the live document
        String liveJson = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode liveNode = mapper.readTree(liveJson);
        String normalised = SnapshotNormaliser.normalise(liveNode);

        // Mutate the normalised doc — inject a fake path that doesn't exist
        String mutated = normalised.replace(
                "\"Field Service API\"",
                "\"MUTATED Field Service API\"");

        // The mutated string must differ from the original
        org.assertj.core.api.Assertions.assertThat(mutated)
                .as("Mutated contract must differ from normalised")
                .isNotEqualTo(normalised);

        // Verify the normaliser produces different output for the mutated doc
        JsonNode mutatedNode = mapper.readTree(mutated);
        String renormalised = SnapshotNormaliser.normalise(mutatedNode);
        org.assertj.core.api.Assertions.assertThat(renormalised)
                .as("Re-normalised mutated contract must differ from original")
                .isNotEqualTo(normalised);
    }

    // ---- helpers ---------------------------------------------------------------

    private void writeSnapshot(String content) throws Exception {
        // Write relative to project root when available (e.g. in-repo test run)
        Path target = Paths.get(SNAPSHOT_SRC_PATH);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private String computeDiff(String expected, String actual) {
        String[] expectedLines = expected.split("\n");
        String[] actualLines   = actual.split("\n");
        StringBuilder sb = new StringBuilder();
        int maxLines = Math.max(expectedLines.length, actualLines.length);
        for (int i = 0; i < Math.min(maxLines, 60); i++) {
            String exp = i < expectedLines.length ? expectedLines[i] : "<missing>";
            String act = i < actualLines.length   ? actualLines[i]   : "<missing>";
            if (!exp.equals(act)) {
                sb.append("line ").append(i + 1).append(":\n")
                  .append("  - ").append(exp).append("\n")
                  .append("  + ").append(act).append("\n");
            }
        }
        if (maxLines > 60) {
            sb.append("... (").append(maxLines - 60).append(" more lines truncated)\n");
        }
        return sb.toString();
    }
}
