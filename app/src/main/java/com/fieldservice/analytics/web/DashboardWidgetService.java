package com.fieldservice.analytics.web;

import com.fieldservice.analytics.KpiProjection;
import com.fieldservice.analytics.KpiProjectionQuery;
import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for the dashboard widget API.
 *
 * <p>Access is restricted to MANAGER and ADMIN via method security, not only the controller
 * (defence-in-depth: ArchUnit enforces the controller performs no repository access).
 *
 * <p>Degraded policy: if a projection exists but was computed under a partial failure,
 * it is returned with {@code degraded = true} and the widget surface exposes it with an
 * explicit indicator. If no projection exists at all, an empty-data widget is emitted
 * so the client always receives an explicit no-data state rather than a 404 or blank.
 */
@Service
public class DashboardWidgetService {

    private static final int MAX_WIDGETS = 50;

    private final KpiProjectionQuery     projectionQuery;
    private final WidgetEtagCalculator   etagCalculator;

    public DashboardWidgetService(KpiProjectionQuery   projectionQuery,
                                  WidgetEtagCalculator etagCalculator) {
        this.projectionQuery = projectionQuery;
        this.etagCalculator  = etagCalculator;
    }

    /**
     * Builds the widget response for the requested (metrics, window, segment) combination.
     *
     * <p>Method security: only MANAGER and ADMIN may call this method.
     *
     * @param metrics      allow-listed metric keys; deduplicated, max 50
     * @param window       observation window (SEVEN_DAYS, THIRTY_DAYS, NINETY_DAYS)
     * @param segmentKey   optional segment filter; {@code "ALL"} if not specified
     * @return a {@link WidgetQueryResult} containing the paged response and computed ETag
     */
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public WidgetQueryResult query(List<WidgetMetricKey> metrics,
                                   WidgetWindow          window,
                                   String                segmentKey) {

        String effectiveSegment = (segmentKey != null && !segmentKey.isBlank()) ? segmentKey : "ALL";

        // Deduplicate, preserve order, cap at max
        List<WidgetMetricKey> deduped = deduplicateAndCap(metrics);

        List<KpiProjection> projections = new ArrayList<>(deduped.size());
        List<WidgetDto>     widgets     = new ArrayList<>(deduped.size());

        for (WidgetMetricKey mk : deduped) {
            Optional<KpiProjection> opt = projectionQuery.findByKey(
                    mk.key(), effectiveSegment, window.windowKey());

            if (opt.isPresent()) {
                KpiProjection p = opt.get();
                projections.add(p);
                widgets.add(WidgetDto.from(p, window.name()));
            } else {
                // Explicit no-data widget (policy A10: never return blank or zero)
                widgets.add(noDataWidget(mk.key(), effectiveSegment, window.name()));
            }
        }

        String etag = etagCalculator.compute(projections);

        PageMeta  meta  = PageMeta.of(0, deduped.size(), deduped.size());
        PageLinks links = PageLinks.of(null, null);
        PagedResponse<WidgetDto> response = PagedResponse.of(widgets, meta, links);

        return new WidgetQueryResult(response, etag);
    }

    // ---- Helpers -----------------------------------------------------------

    private List<WidgetMetricKey> deduplicateAndCap(List<WidgetMetricKey> metrics) {
        Map<WidgetMetricKey, Boolean> seen = new LinkedHashMap<>();
        for (WidgetMetricKey mk : metrics) {
            seen.put(mk, Boolean.TRUE);
            if (seen.size() == MAX_WIDGETS) break;
        }
        return new ArrayList<>(seen.keySet());
    }

    private WidgetDto noDataWidget(String metricKey, String segment, String window) {
        return new WidgetDto(
                metricKey,
                segment,
                window,
                null,   // value — explicitly null
                null,   // unit
                null,   // numerator
                null,   // denominator
                0,      // sampleCount
                "PROVISIONAL",
                java.time.Instant.EPOCH,
                Long.MAX_VALUE,
                true,   // degraded = true for no-data
                "NO_DATA"
        );
    }

    /**
     * Carries the paged widget response and its computed ETag together so the
     * controller can write both without calling the service twice.
     */
    public record WidgetQueryResult(
            PagedResponse<WidgetDto> response,
            String etag
    ) {}
}
