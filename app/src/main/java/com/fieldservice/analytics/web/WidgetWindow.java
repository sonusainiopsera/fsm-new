package com.fieldservice.analytics.web;

/**
 * Allow-listed observation windows for dashboard widget queries.
 *
 * <p>Maps the client-facing enum name to the internal projection storage key ({@code P7D},
 * {@code P30D}, {@code P90D}) so the HTTP API contract is decoupled from the storage format.
 */
public enum WidgetWindow {

    SEVEN_DAYS  ("P7D"),
    THIRTY_DAYS ("P30D"),
    NINETY_DAYS ("P90D");

    private final String windowKey;

    WidgetWindow(String windowKey) { this.windowKey = windowKey; }

    public String windowKey() { return windowKey; }
}
