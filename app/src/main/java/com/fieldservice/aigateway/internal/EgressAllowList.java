package com.fieldservice.aigateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * Guards egress by validating the provider host against a configured allow-list.
 *
 * <p>An empty allow-list causes all calls to be refused — the gateway never defaults to allow-all.
 * Callers cannot supply a URL; the host is always resolved from configuration.
 */
@Component
class EgressAllowList {

    private static final Logger log = LoggerFactory.getLogger(EgressAllowList.class);

    private final List<String> allowedHosts;

    EgressAllowList(AiGatewayProperties properties) {
        this.allowedHosts = properties.provider().allowedHosts();
    }

    /**
     * Asserts that the host derived from the configured base URL is on the allow-list.
     *
     * @param configuredBaseUrl the provider base URL from configuration (never from a request payload)
     * @throws EgressBlockedException if the host is not allow-listed or the list is empty
     */
    void assertAllowed(String configuredBaseUrl) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            log.warn("security_event=egress_blocked reason=empty_allowlist url={}", configuredBaseUrl);
            throw new EgressBlockedException("Egress refused: allow-list is empty — all AI calls disabled");
        }

        String host = extractHost(configuredBaseUrl);
        boolean permitted = allowedHosts.stream()
                .map(String::toLowerCase)
                .anyMatch(h -> h.equals(host.toLowerCase()));

        if (!permitted) {
            log.warn("security_event=egress_blocked reason=host_not_allowlisted host={}", host);
            throw new EgressBlockedException("Egress refused: host '" + host + "' is not on the allow-list");
        }
    }

    private static String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new EgressBlockedException("Egress refused: malformed provider URL");
        }
    }

    static class EgressBlockedException extends RuntimeException {
        EgressBlockedException(String message) { super(message); }
    }
}
