package com.fieldservice.privacy.api;

/**
 * Unit of time for a retention period.
 *
 * <p>MONTHS and YEARS use calendar arithmetic (via {@code ZonedDateTime}) rather than
 * fixed-length days so that month and year boundaries are preserved correctly across
 * daylight-saving transitions and leap years.
 */
public enum RetentionPeriodUnit {
    DAYS,
    MONTHS,
    YEARS
}
