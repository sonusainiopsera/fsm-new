package com.fieldservice.geo.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;

/**
 * SSRF guard: validates the configured provider host against a static allow-list at
 * startup. A failed check throws so the application context never refreshes with a
 * misconfigured provider URL.
 *
 * <p>Callers never supply a URL — the host is always resolved from configuration.
 * The allow-list is intentionally checked at construction time (context startup),
 * not lazily on the first call, so misconfiguration fails fast.
 */
class TravelProviderAllowList {

    private static final Logger log = LoggerFactory.getLogger(TravelProviderAllowList.class);

    private final List<String> allowedHosts;

    TravelProviderAllowList(TravelProviderProperties props) {
        this.allowedHosts = props.provider().allowedHosts();
        validateAtStartup(props.provider().baseUrl());
    }

    private void validateAtStartup(String configuredBaseUrl) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalStateException(
                    "geo.travel.provider.allowed-hosts is empty — all travel-time calls are refused. "
                    + "Configure at least one allowed host.");
        }
        String host = extractHost(configuredBaseUrl);
        boolean permitted = allowedHosts.stream()
                .map(String::toLowerCase)
                .anyMatch(h -> h.equals(host.toLowerCase()));
        if (!permitted) {
            throw new IllegalStateException(
                    "Travel provider host '" + host + "' is not in the allow-list " + allowedHosts
                    + ". Update geo.travel.provider.allowed-hosts or geo.travel.provider.base-url.");
        }
        log.info("geo.travel.allow_list_validated host={}", host);
    }

    /**
     * Asserts the configured host is allowed. Called before every outbound request
     * as a defence-in-depth check (startup is the primary gate).
     */
    void assertAllowed(String configuredBaseUrl) {
        String host = extractHost(configuredBaseUrl);
        boolean permitted = allowedHosts.stream()
                .map(String::toLowerCase)
                .anyMatch(h -> h.equals(host.toLowerCase()));
        if (!permitted) {
            log.warn("security_event=egress_blocked component=travel host={}", host);
            throw new EgressBlockedException("Travel provider host not allowed: " + host);
        }
    }

    private static String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) throw new IllegalStateException("Malformed travel provider URL: " + url);
            return uri.getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed travel provider URL: " + url, e);
        }
    }

    static class EgressBlockedException extends RuntimeException {
        EgressBlockedException(String message) { super(message); }
    }
}
