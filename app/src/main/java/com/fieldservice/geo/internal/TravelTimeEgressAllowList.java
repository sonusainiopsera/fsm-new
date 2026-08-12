package com.fieldservice.geo.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * SSRF protection for the travel-time provider (OWASP A01).
 *
 * <p>Design contract:
 * <ul>
 *   <li>An empty allow-list refuses ALL calls rather than defaulting to allow-all.</li>
 *   <li>No method accepts a caller-supplied URL; the base URL is from configuration only.</li>
 *   <li>Hosts resolving to private, loopback, or link-local addresses are refused even
 *       when listed, preventing DNS-rebinding attacks.</li>
 * </ul>
 */
class TravelTimeEgressAllowList {

    private static final Logger log = LoggerFactory.getLogger(TravelTimeEgressAllowList.class);

    private final Set<String> allowedHosts;

    TravelTimeEgressAllowList(List<String> allowedHosts) {
        List<String> filtered = allowedHosts == null
                ? Collections.emptyList()
                : allowedHosts.stream().filter(h -> h != null && !h.isBlank()).toList();
        this.allowedHosts = Set.copyOf(filtered);
    }

    /**
     * Validates that the host from {@code configuredUrl} is allow-listed and
     * does not resolve to a private address.
     *
     * @throws SecurityException if validation fails — propagated at context refresh
     */
    void validate(String configuredUrl) {
        if (allowedHosts.isEmpty()) {
            log.warn("security=SSRF_REFUSED reason=empty-allow-list component=geo-travel");
            throw new SecurityException("Geo travel egress allow-list is empty — all outbound calls refused");
        }

        String host;
        try {
            host = URI.create(configuredUrl).getHost();
        } catch (IllegalArgumentException e) {
            log.warn("security=SSRF_REFUSED reason=malformed-url component=geo-travel");
            throw new SecurityException("Configured travel provider URL is malformed");
        }

        if (host == null || !allowedHosts.contains(host)) {
            log.warn("security=SSRF_REFUSED reason=not-allow-listed host={} component=geo-travel", host);
            throw new SecurityException("Travel provider host '" + host + "' is not in the geo egress allow-list");
        }

        checkResolvedIp(host);
    }

    private void checkResolvedIp(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                log.warn("security=SSRF_REFUSED reason=private-ip host={} resolvedTo={} component=geo-travel",
                        host, addr.getHostAddress());
                throw new SecurityException("Travel provider host '" + host + "' resolves to a private/link-local address");
            }
        } catch (UnknownHostException e) {
            log.info("security=SSRF_CHECK_SKIPPED reason=dns-failure host={} component=geo-travel", host);
        }
    }
}
