package com.fieldservice.analytics.internal;

import com.fieldservice.analytics.KpiProjection;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Computes a deterministic, instance-independent strong ETag for a set of KPI widget projections.
 *
 * <p>The ETag is a SHA-256 hash over the lexicographically ordered tuple of
 * (metricKey, segmentKey, windowKey, projectionVersion, value, dataAsOf) for every
 * requested projection. Because the input is sorted deterministically before hashing,
 * any instance serving the same projection state produces an identical ETag —
 * round-robin load balancing cannot cause spurious 200 responses.
 *
 * <p>The returned string already includes the surrounding double-quotes required by
 * RFC 7232 (strong ETag format: {@code "abc123..."}).
 */
@Component
public class WidgetEtagCalculator {

    /**
     * Computes a strong ETag for the given projections.
     *
     * @param projections the projections to hash; may be empty (returns a stable empty-list ETag)
     * @return RFC 7232 strong ETag value including surrounding double quotes
     */
    public String compute(List<KpiProjection> projections) {
        List<KpiProjection> sorted = projections.stream()
                .sorted(Comparator.comparing(KpiProjection::metricKey)
                        .thenComparing(KpiProjection::segmentKey)
                        .thenComparing(KpiProjection::windowKey))
                .toList();

        StringBuilder sb = new StringBuilder();
        for (KpiProjection p : sorted) {
            sb.append(p.metricKey()).append('|')
              .append(p.segmentKey()).append('|')
              .append(p.windowKey()).append('|')
              .append(p.projectionVersion()).append('|')
              .append(p.value() != null ? p.value().stripTrailingZeros().toPlainString() : "null").append('|')
              .append(p.dataAsOf().toEpochMilli()).append('\n');
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return '"' + encoded + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
