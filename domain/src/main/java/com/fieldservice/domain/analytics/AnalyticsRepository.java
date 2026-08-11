package com.fieldservice.domain.analytics;

import com.fieldservice.platform.security.UnscopedRead;
import org.springframework.stereotype.Repository;

/**
 * Analytics read model — accesses aggregated data that intentionally spans
 * all tenants and all rows. This is the only authorised use of
 * {@link UnscopedRead} in the platform.
 *
 * <p>Permitted usage is recorded in
 * {@code platform/src/main/resources/unscoped-read-allowlist.txt}.
 * The {@code UnscopedReadAllowListTest} enforces this at build time.
 *
 * <p>Access to this repository is restricted to ADMIN and MANAGER roles
 * via {@code @PreAuthorize} on every consuming service method.
 */
@Repository
@UnscopedRead(justification =
        "The analytics read model aggregates KPIs and SLA metrics across the "
                + "full dataset for operations managers and admins. Row-scoping is "
                + "semantically incorrect here — a manager KPI showing 47 open work "
                + "orders must reflect the true total, not a filtered subset. "
                + "Access is restricted to ADMIN and MANAGER roles at the service layer.")
public class AnalyticsRepository {
    // Placeholder: aggregate query methods will be added in analytics WOs.
    // Every method that reads data must be called only from service methods
    // annotated @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')").
}
