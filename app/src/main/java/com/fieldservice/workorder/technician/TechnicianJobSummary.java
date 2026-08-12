package com.fieldservice.workorder.technician;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compact read-only projection for the technician day-list endpoint.
 *
 * <p>Contains only the fields a field technician needs before arrival.
 * The entity graph is never serialised into this record; values come from
 * an explicit JDBC projection query.
 *
 * <h3>Privacy</h3>
 * <ul>
 *   <li>{@code contactPhoneMasked} exposes only the last four digits of the
 *       site contact phone — see {@link ContactMasker#maskPhone}.</li>
 *   <li>No customer email, no internal audit fields, no password hashes.</li>
 * </ul>
 */
public record TechnicianJobSummary(
        UUID       id,
        String     reference,
        String     priority,
        String     state,
        Instant    scheduledWindowStart,
        Instant    scheduledWindowEnd,
        String     siteName,
        String     siteAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String     assetTag,
        String     assetDescription,
        String     faultSummary,
        Instant    resolutionDeadline,
        boolean    slaAtRisk,
        String     contactPhoneMasked
) {}
