package com.fieldservice.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable daily trend data point returned by the analytics read model.
 *
 * <p>Each point represents the value of a metric on a specific UTC calendar date,
 * recorded once per day by {@code TrendPointWriter} and never overwritten thereafter.
 */
public record TrendPointDto(LocalDate bucketDate, BigDecimal value) {}
