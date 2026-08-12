package com.fieldservice.analytics.web;

/**
 * Allow-listed rolling window enum for the dashboard widget API (WO-166).
 *
 * <p>Maps the API-facing constant name to the internal {@code window_key}
 * stored in {@code kpi_projection}. Unknown values are rejected with a 400 field error.
 */
public enum WindowKey {

    SEVEN_DAYS("ROLLING_7D", 7),
    THIRTY_DAYS("ROLLING_30D", 30),
    NINETY_DAYS("ROLLING_90D", 90);

    private final String internalKey;
    private final int days;

    WindowKey(String internalKey, int days) {
        this.internalKey = internalKey;
        this.days = days;
    }

    public String internalKey()  { return internalKey; }
    public int    days()         { return days; }
    public String deltaKey()     { return internalKey + "_DELTA"; }
}
