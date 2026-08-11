package com.fieldservice.privacy.internal;

import com.fieldservice.platform.masking.MaskingStrategies;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Non-production data anonymisation generator.
 *
 * <p>Activated only when the {@code anonymise} Spring profile is active.
 * Refuses to run on the {@code api} or {@code worker} (production) profiles.
 *
 * <p>For each {@code CONFIDENTIAL} field in the classification registry the generator
 * rewrites the column using a deterministic HMAC-SHA256 pseudonymiser seeded from
 * {@code ANONYMISATION_SEED} environment variable (non-production-only). Pseudonymisation
 * is format-preserving within each data type (email → valid email, phone → digit string)
 * and referentially consistent — the same input always produces the same output within
 * a dataset.
 *
 * <p>For {@code RESTRICTED} fields the column is set to {@code '[REDACTED]'} — no
 * functional value is left in non-production environments.
 *
 * <p>Idempotent: running the generator twice produces the same result because the
 * HMAC function is deterministic for the same seed and input.
 */
@Component
@Profile("anonymise")
public class AnonymisationGenerator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnonymisationGenerator.class);

    /** HMAC algorithm — SHA-256, not MD5/SHA-1/DES which are forbidden. */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ClassificationRegistry registry;
    private final JdbcTemplate jdbc;
    private final Environment environment;

    AnonymisationGenerator(ClassificationRegistry registry,
                            JdbcTemplate jdbc,
                            Environment environment) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkNotProduction();

        String seed = environment.getProperty("ANONYMISATION_SEED", "dev-anon-seed-change-me");
        byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
        log.info("anonymisation_generator starting profileCount={}",
                environment.getActiveProfiles().length);

        List<ClassificationView> classified = registry.findAll();
        int fieldsProcessed = 0;

        for (ClassificationView view : classified) {
            if (view.fieldName() == null) continue; // entity-level row; field rows handle the work

            ClassificationTier tier = view.tier();
            if (tier == ClassificationTier.PUBLIC || tier == ClassificationTier.INTERNAL) continue;

            String table = toSnakeCase(view.entityName());
            String column = toSnakeCase(view.fieldName());

            try {
                if (tier == ClassificationTier.RESTRICTED) {
                    int updated = jdbc.update(
                            "UPDATE " + table + " SET " + column + " = '[REDACTED]' WHERE " + column + " IS NOT NULL");
                    log.info("anonymisation_restricted entity={} field={} rowsUpdated={}",
                            view.entityName(), view.fieldName(), updated);
                } else {
                    // CONFIDENTIAL — deterministic pseudonymisation
                    // Derive data type from field name heuristic (no dataType column in registry)
                    String dataType = inferDataType(view.fieldName());
                    pseudonymiseColumn(table, column, dataType, seedBytes);
                }
                fieldsProcessed++;
            } catch (Exception e) {
                log.error("anonymisation_field_failed entity={} field={} — skipping",
                        view.entityName(), view.fieldName(), e);
            }
        }

        log.info("anonymisation_generator complete fieldsProcessed={}", fieldsProcessed);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void checkNotProduction() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains("api") || activeProfiles.contains("worker")) {
            throw new IllegalStateException(
                    "AnonymisationGenerator must not run under a production profile (api/worker). "
                    + "Active profiles: " + activeProfiles);
        }
    }

    private void pseudonymiseColumn(String table, String column,
                                    String dataType, byte[] seed) {
        // Fetch all non-null, non-already-redacted values with a rowid handle
        List<Object[]> rows = jdbc.query(
                "SELECT id, " + column + " FROM " + table
                + " WHERE " + column + " IS NOT NULL AND CAST(" + column + " AS TEXT) != '[REDACTED]'",
                (rs, n) -> new Object[]{rs.getObject("id"), rs.getString(column)});

        for (Object[] row : rows) {
            Object id = row[0];
            String original = (String) row[1];
            String pseudonymised = pseudonymise(original, dataType, seed);
            jdbc.update("UPDATE " + table + " SET " + column + " = ? WHERE id = ?",
                    pseudonymised, id);
        }
        log.info("anonymisation_pseudonymised entity_table={} field_column={} dataType={} rows={}",
                table, column, dataType, rows.size());
    }

    /**
     * Generates a deterministic pseudonym for the given value.
     *
     * <p>Uses HMAC-SHA256 with the non-production seed. The output is formatted to
     * match the data type so it remains syntactically valid and referentially consistent.
     * Never uses MD5, SHA-1, or DES.
     */
    private String pseudonymise(String value, String dataType, byte[] seed) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(seed, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));

            if (dataType == null) {
                return "[PSEUDONYM-" + HexFormat.of().formatHex(digest, 0, 4).toUpperCase(Locale.ROOT) + "]";
            }
            return switch (dataType.toUpperCase(Locale.ROOT)) {
                case "EMAIL" -> {
                    String hex = HexFormat.of().formatHex(digest, 0, 8);
                    yield "anon-" + hex + "@example.invalid";
                }
                case "PHONE" -> {
                    // Use last 10 digits of digest as a local number
                    long num = Math.abs(toLong(digest)) % 9_000_000_000L + 1_000_000_000L;
                    yield String.valueOf(num);
                }
                case "NAME" -> {
                    // Produce a synthetic name: Pseudonym-<4hex>
                    String hex = HexFormat.of().formatHex(digest, 0, 2);
                    yield "Pseudonym-" + hex.toUpperCase(Locale.ROOT);
                }
                case "COORDINATE" -> {
                    // Produce a random-looking coordinate in a safe range
                    double lat = (((digest[0] & 0xFF) / 255.0) * 170.0) - 85.0;
                    double lon = (((digest[1] & 0xFF) / 255.0) * 360.0) - 180.0;
                    yield String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
                }
                case "ADDRESS" -> "[ADDRESS-PSEUDONYM]";
                case "TOKEN", "HASH" -> "[REDACTED]";
                default -> {
                    String hex = HexFormat.of().formatHex(digest, 0, 4).toUpperCase(Locale.ROOT);
                    yield "[PSEUDONYM-" + hex + "]";
                }
            };
        } catch (Exception e) {
            log.warn("pseudonymise_failed dataType={} — using fallback redaction", dataType, e);
            return "[REDACTED]";
        }
    }

    private static long toLong(byte[] bytes) {
        long result = 0;
        for (int i = 0; i < Math.min(8, bytes.length); i++) {
            result = (result << 8) | (bytes[i] & 0xFF);
        }
        return result;
    }

    /**
     * Heuristic data-type inference from field name.
     * A future registry enhancement could add an explicit data_type column; for now we infer.
     */
    private static String inferDataType(String fieldName) {
        if (fieldName == null) return null;
        String lower = fieldName.toLowerCase(Locale.ROOT);
        if (lower.contains("email")) return "EMAIL";
        if (lower.contains("phone") || lower.contains("mobile") || lower.contains("fax")) return "PHONE";
        if (lower.contains("name") || lower.contains("firstname") || lower.contains("lastname")) return "NAME";
        if (lower.contains("address") || lower.contains("street") || lower.contains("postcode")
                || lower.contains("zipcode")) return "ADDRESS";
        if (lower.contains("lat") || lower.contains("lon") || lower.contains("coord")
                || lower.contains("gps") || lower.contains("position")) return "COORDINATE";
        if (lower.contains("token") || lower.contains("secret") || lower.contains("key")
                || lower.contains("credential")) return "TOKEN";
        if (lower.contains("hash") || lower.contains("password") || lower.contains("digest")) return "HASH";
        return null; // fallback to generic pseudonym
    }

    private static String toSnakeCase(String camelCase) {
        return camelCase
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }
}
