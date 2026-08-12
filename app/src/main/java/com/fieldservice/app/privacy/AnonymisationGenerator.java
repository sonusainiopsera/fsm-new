package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Non-production data anonymisation generator.
 *
 * <p>Activated only under the {@code anonymise} Spring profile.  Reads the classification
 * registry, walks every {@code CONFIDENTIAL} entity/field and rewrites values with
 * deterministic HMAC-SHA256-derived pseudonyms, preserving referential integrity.
 * {@code RESTRICTED} columns are set to a fixed non-functional placeholder.
 *
 * <p><strong>Safety guards:</strong>
 * <ul>
 *   <li>Refuses to run if the active profiles include {@code prod}.</li>
 *   <li>Uses HmacSHA256 — MD5, SHA-1 and DES are never used.</li>
 *   <li>Pseudonyms are format-preserving where possible (email, name) so foreign-key
 *       lookups and display constraints remain valid.</li>
 * </ul>
 *
 * <p>Run via: {@code java -jar app.jar --spring.profiles.active=anonymise}
 * with the {@code APP_ANONYMISE_SEED} environment variable set to a non-production secret.
 */
@Component
@Profile("anonymise")
public class AnonymisationGenerator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnonymisationGenerator.class);

    private static final String RESTRICTED_PLACEHOLDER = "[ANONYMISED-RESTRICTED]";
    private static final int HMAC_TRUNCATE_BYTES = 8;

    private final ClassificationRegistry registry;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final String seed;

    public AnonymisationGenerator(ClassificationRegistry registry,
                                   JdbcTemplate jdbc,
                                   Environment environment) {
        this.registry    = registry;
        this.jdbc        = jdbc;
        this.environment = environment;
        this.seed        = environment.getProperty("app.privacy.anonymise.seed",
                "change-me-non-production-seed-only");
    }

    @Override
    public void run(ApplicationArguments args) {
        guardAgainstProduction();

        log.info("anonymisation_start profiles={}", Arrays.toString(environment.getActiveProfiles()));

        List<ClassificationView> allRows = registry.findAll();
        int updated = 0;

        for (ClassificationView view : allRows) {
            if (view.tier() == ClassificationTier.RESTRICTED) {
                updated += anonymiseRestricted(view);
            } else if (view.tier() == ClassificationTier.CONFIDENTIAL && view.fieldName() != null) {
                updated += anonymiseConfidential(view);
            }
        }

        log.info("anonymisation_complete rows_updated={}", updated);
    }

    // ---- guards ---------------------------------------------------------------

    private void guardAgainstProduction() {
        String[] profiles = environment.getActiveProfiles();
        for (String p : profiles) {
            if ("prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p)) {
                throw new IllegalStateException(
                        "AnonymisationGenerator refused: 'prod' profile is active. "
                        + "This command must only run against non-production databases.");
            }
        }
    }

    // ---- RESTRICTED columns ---------------------------------------------------

    private int anonymiseRestricted(ClassificationView view) {
        if (view.fieldName() == null) return 0;
        String tableName  = toSnake(view.entityName());
        String columnName = toSnake(view.fieldName());
        try {
            int rows = jdbc.update("UPDATE " + tableName + " SET " + columnName + " = ?",
                    RESTRICTED_PLACEHOLDER);
            log.info("anonymise_restricted table={} column={} rows={}", tableName, columnName, rows);
            return rows;
        } catch (Exception e) {
            log.warn("anonymise_restricted_skipped table={} column={} reason={}", tableName, columnName, e.getMessage());
            return 0;
        }
    }

    // ---- CONFIDENTIAL columns -------------------------------------------------

    private int anonymiseConfidential(ClassificationView view) {
        String tableName  = toSnake(view.entityName());
        String columnName = toSnake(view.fieldName());
        try {
            // Fetch distinct values so each unique value maps to the same pseudonym
            List<String> values = jdbc.queryForList(
                    "SELECT DISTINCT " + columnName + " FROM " + tableName
                    + " WHERE " + columnName + " IS NOT NULL",
                    String.class);

            int rows = 0;
            for (String original : values) {
                if (original == null || original.startsWith("[ANONYMISED")) continue;
                String pseudonym = pseudonymise(original, columnName);
                rows += jdbc.update(
                        "UPDATE " + tableName + " SET " + columnName + " = ? WHERE " + columnName + " = ?",
                        pseudonym, original);
            }
            log.info("anonymise_confidential table={} column={} distinct={} rows={}", tableName, columnName, values.size(), rows);
            return rows;
        } catch (Exception e) {
            log.warn("anonymise_confidential_skipped table={} column={} reason={}", tableName, columnName, e.getMessage());
            return 0;
        }
    }

    // ---- Pseudonymisation (HMAC-SHA256, NOT MD5/SHA-1/DES) -------------------

    String pseudonymise(String original, String contextHint) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((contextHint + ":" + original).getBytes(StandardCharsets.UTF_8));
            // Truncate to first 8 bytes → 16 hex chars
            byte[] truncated = Arrays.copyOf(digest, HMAC_TRUNCATE_BYTES);
            String hex = HexFormat.of().formatHex(truncated);

            // Format-preservation heuristics
            if (original.contains("@")) {
                return "anon-" + hex + "@example-anon.invalid";
            }
            if (original.matches(".*\\d{4,}.*")) {
                // Looks like a phone/ID — prefix with 0000 then hex digits
                return "0000" + hex;
            }
            // Generic string — base64url short form
            return "anon-" + Base64.getUrlEncoder().withoutPadding().encodeToString(truncated);
        } catch (Exception e) {
            return "[ANONYMISED]";
        }
    }

    // ---- Utility --------------------------------------------------------------

    private static String toSnake(String camelCase) {
        if (camelCase == null) return "";
        return camelCase.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }
}
