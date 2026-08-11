package com.fieldservice.aigateway.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * SSRF protection: validates outbound AI provider URIs against a configured FQDN allow-list.
 *
 * <p>Rules enforced (OWASP A10 — Server-Side Request Forgery):
 * <ol>
 *   <li>Allow-list must not be empty — empty list refuses all calls.</li>
 *   <li>Host must appear in the allow-list (case-insensitive).</li>
 *   <li>Host must not resolve to a private or link-local address.</li>
 * </ol>
 *
 * <p>Refusals are logged as SECURITY events including the refused host.
 */
class EgressAllowList {

    private static final Logger log = LoggerFactory.getLogger(EgressAllowList.class);

    private static final Set<String> PRIVATE_RANGES = Set.of(
            "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.", "172.21.",
            "172.22.", "172.23.", "172.24.", "172.25.", "172.26.", "172.27.", "172.28.",
            "172.29.", "172.30.", "172.31.", "192.168.", "127.", "169.254.", "::1",
            "fc", "fd"
    );

    private final List<String> allowedHosts;

    EgressAllowList(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null
                ? allowedHosts.stream().map(String::toLowerCase).toList()
                : List.of();
    }

    /**
     * Validates that {@code uri} is permitted for egress.
     *
     * @param uri the fully-resolved provider URI (from config — never from a request payload)
     * @throws EgressRefusedException if the host is not on the allow-list or resolves to a private address
     */
    void validate(URI uri) {
        if (allowedHosts.isEmpty()) {
            log.warn("SECURITY: egress refused — allow-list is empty; host={}", uri.getHost());
            throw new EgressRefusedException("Egress refused: AI provider allow-list is empty");
        }

        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        if (!allowedHosts.contains(host)) {
            log.warn("SECURITY: egress refused — host not in allow-list; host={}", host);
            throw new EgressRefusedException("Egress refused: host not in allow-list");
        }

        if (isPrivateOrLinkLocal(host)) {
            log.warn("SECURITY: egress refused — private/link-local address; host={}", host);
            throw new EgressRefusedException("Egress refused: private or link-local address");
        }
    }

    private boolean isPrivateOrLinkLocal(String host) {
        // Block numeric IP prefixes that are private
        for (String prefix : PRIVATE_RANGES) {
            if (host.startsWith(prefix)) return true;
        }
        // Attempt DNS resolution to catch resolved private IPs
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isMulticastAddress();
        } catch (Exception e) {
            // DNS failure counts as a security event — refuse
            log.warn("SECURITY: egress refused — DNS resolution failed; host={}", host);
            return true;
        }
    }

    /** Thrown when egress is refused. Never propagated to HTTP responses. */
    static final class EgressRefusedException extends RuntimeException {
        EgressRefusedException(String message) {
            super(message);
        }
    }
}
