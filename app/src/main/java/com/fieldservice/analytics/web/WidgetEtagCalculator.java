package com.fieldservice.analytics.web;

import com.fieldservice.analytics.KpiProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes a deterministic, strong ETag for a set of {@link KpiProjection} objects.
 *
 * <p>Determinism guarantee:
 * <ul>
 *   <li>Projections are sorted by a stable key ({@code metricKey|segmentKey|windowKey})
 *       before hashing so any permutation of the input produces the same ETag.</li>
 *   <li>The hash input is a UTF-8 string built from ordered
 *       {@code (metricKey, segmentKey, windowKey, projectionVersion, value, dataAsOf)}
 *       tuples separated by pipe characters that cannot appear in any field value.</li>
 *   <li>SHA-256 is used as the hash function; it is instance-independent by definition.</li>
 * </ul>
 *
 * <p>This guarantees that round-robin load balancing does not produce spurious 200 responses
 * when two instances serve the same projection state.
 */
@Component
public class WidgetEtagCalculator {

    private static final Logger log = LoggerFactory.getLogger(WidgetEtagCalculator.class);

    private static final Comparator<KpiProjection> STABLE_ORDER =
            Comparator.comparing(KpiProjection::metricKey)
                      .thenComparing(KpiProjection::segmentKey)
                      .thenComparing(KpiProjection::windowKey);

    /**
     * Computes a quoted strong ETag for the given projections.
     *
     * @param projections the projections to hash; must not be null
     * @return a quoted ETag string, e.g. {@code "\"a3f2c1…\""}
     */
    public String compute(List<KpiProjection> projections) {
        if (projections.isEmpty()) {
            return "\"empty\"";
        }

        List<KpiProjection> sorted = projections.stream()
                .sorted(STABLE_ORDER)
                .toList();

        StringBuilder sb = new StringBuilder(sorted.size() * 80);
        for (KpiProjection p : sorted) {
            sb.append(p.metricKey()).append('|')
              .append(p.segmentKey()).append('|')
              .append(p.windowKey()).append('|')
              .append(p.projectionVersion()).append('|')
              .append(p.value() != null ? p.value().toPlainString() : "null").append('|')
              .append(p.dataAsOf() != null ? p.dataAsOf().toString() : "null")
              .append('\n');
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(hash).substring(0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available — ETag computation failed", e);
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
