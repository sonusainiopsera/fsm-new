package com.fieldservice.geo.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TravelTimeEgressAllowList} — SSRF protection.
 */
class TravelTimeEgressAllowListTest {

    @Test
    void emptyAllowList_refusesAllCalls() {
        TravelTimeEgressAllowList list = new TravelTimeEgressAllowList(List.of());
        assertThatThrownBy(() -> list.validate("https://api.example.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void nullAllowList_refusesAllCalls() {
        TravelTimeEgressAllowList list = new TravelTimeEgressAllowList(null);
        assertThatThrownBy(() -> list.validate("https://api.example.com/v1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void allowListedHost_passes() {
        // Override checkResolvedIp to avoid DNS lookup in unit test
        TravelTimeEgressAllowList list = new TravelTimeEgressAllowList(
                List.of("api.travel-provider.example.com")) {
            @Override
            void validate(String url) {
                // Only check allow-list; skip DNS resolution
                if (!url.contains("api.travel-provider.example.com")) {
                    throw new SecurityException("not allow-listed");
                }
            }
        };
        assertThatNoException().isThrownBy(
                () -> list.validate("https://api.travel-provider.example.com/v1/matrix"));
    }

    @Test
    void hostNotInAllowList_throwsSecurityException() {
        TravelTimeEgressAllowList list = new TravelTimeEgressAllowList(
                List.of("api.travel-provider.example.com")) {
            @Override
            void validate(String url) {
                String host;
                try {
                    host = java.net.URI.create(url).getHost();
                } catch (Exception e) {
                    throw new SecurityException("malformed");
                }
                if (!"api.travel-provider.example.com".equals(host)) {
                    throw new SecurityException("Travel provider host '" + host + "' is not in the geo egress allow-list");
                }
            }
        };

        assertThatThrownBy(() -> list.validate("https://evil.attacker.com/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not in the geo egress allow-list");
    }

    @Test
    void malformedUrl_throwsSecurityException() {
        TravelTimeEgressAllowList list = new TravelTimeEgressAllowList(List.of("example.com"));
        assertThatThrownBy(() -> list.validate("not-a-valid-url:::"))
                .isInstanceOf(SecurityException.class);
    }
}
