package com.fieldservice.privacy.api;

import java.util.Map;

/**
 * O7 guardrail metrics: fulfilment rate and open request distribution.
 *
 * @param totalClosed          total closed requests (FULFILLED + REJECTED + WITHDRAWN)
 * @param fulfilledInTime      count fulfilled within the 30-day SLA
 * @param fulfilledLate        count fulfilled after the 30-day SLA
 * @param fulfilmentRatePct    percentage fulfilled in time over total closed (0–100)
 * @param openByRemainingDays  map of remaining-day bucket (e.g. "0-5","6-14","15+") to count
 */
public record FulfillmentMetrics(
        long totalClosed,
        long fulfilledInTime,
        long fulfilledLate,
        double fulfilmentRatePct,
        Map<String, Long> openByRemainingDays
) {}
