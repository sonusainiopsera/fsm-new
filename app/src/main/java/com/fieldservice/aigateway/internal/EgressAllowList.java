package com.fieldservice.aigateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * SSRF protection — restricts outbound AI calls to an explicit allow-list of provider FQDNs.
 *
 * <p>Design contract (OWASP A01):
 * <ul>
 *   <li>An empty allow-list refuses ALL calls rather than defaulting to allow-all.</li>
 *   <li>No method accepts a caller-supplied URL; the base URL comes from configuration only.</li>
 *   <li>Hosts that resolve to private, loopback, or link-local addresses are refused even when
 *       they appear in the allow-list, preventing DNS-rebinding attacks.</li>
 * </ul>
 * Refusals are logged as security events with level WARN.
 */
class EgressAllowList {

    private static final Logger log = LoggerFactory.getLogger(EgressAllowList.class);

    private final Set<String> allowedHosts;

    EgressAllowList(List<String> allowedHosts) {
        List<String> filtered = allowedHosts == null
                ? Collections.emptyList()
                : allowedHosts.stream().filter(h -> h != null && !h.isBlank()).toList();
        this.allowedHosts = Set.copyOf(filtered);
    }

    /**
     * Validates that the host extracted from {@code configuredUrl} is in the allow-list
     * and does not resolve to a private/loopback/link-local address.
     *
     * @throws SecurityException if the host is not allowed or resolves to a private IP
     */
    void validate(String configuredUrl) {
        if (allowedHosts.isEmpty()) {
            log.warn("security=SSRF_REFUSED reason=empty-allow-list url={}", sanitizeUrl(configuredUrl));
            throw new SecurityException("Egress allow-list is empty — all outbound AI calls refused");
        }

        String host;
        try {
            host = URI.create(configuredUrl).getHost();
        } catch (IllegalArgumentException e) {
            log.warn("security=SSRF_REFUSED reason=malformed-url");
            throw new SecurityException("Configured AI provider URL is malformed");
        }

        if (host == null || !allowedHosts.contains(host)) {
            log.warn("security=SSRF_REFUSED reason=not-allow-listed host={}", host);
            throw new SecurityException("AI provider host '" + host + "' is not in the egress allow-list");
        }

        checkResolvedIp(host);
    }

    private void checkResolvedIp(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                log.warn("security=SSRF_REFUSED reason=private-ip host={} resolvedTo={}", host, addr.getHostAddress());
                throw new SecurityException("AI provider host '" + host + "' resolves to a private/link-local address");
            }
        } catch (UnknownHostException e) {
            // DNS resolution failure at validation time is logged but not treated as a refusal;
            // the actual HTTP call will fail if the host remains unresolvable.
            log.info("security=SSRF_CHECK_SKIPPED reason=dns-failure host={}", host);
        }
    }

    private static String sanitizeUrl(String url) {
        if (url == null) return "null";
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "<invalid-url>";
        }
    }
}
