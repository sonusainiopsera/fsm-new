package com.fieldservice.geo;

import com.fieldservice.geo.internal.TravelProviderAllowList;
import com.fieldservice.geo.internal.TravelProviderProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link TravelProviderAllowList} — SSRF protection at startup.
 * No network access required.
 */
class TravelProviderAllowListTest {

    @Test
    @DisplayName("allowed host in allow-list: no exception at startup")
    void allowedHost_noException() {
        assertThatCode(() -> buildAllowList("https://maps.example.com", List.of("maps.example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("host not in allow-list: throws IllegalStateException at startup")
    void disallowedHost_throwsAtStartup() {
        assertThatThrownBy(() -> buildAllowList("https://evil.example.com", List.of("maps.example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evil.example.com")
                .hasMessageNotContaining("api-key");
    }

    @Test
    @DisplayName("empty allow-list: throws IllegalStateException at startup")
    void emptyAllowList_throwsAtStartup() {
        assertThatThrownBy(() -> buildAllowList("https://maps.example.com", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("malformed URL: throws IllegalStateException at startup")
    void malformedUrl_throwsAtStartup() {
        assertThatThrownBy(() -> buildAllowList("not-a-valid-url!!!://broken", List.of("maps.example.com")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("assertAllowed: passes for allowed host")
    void assertAllowed_passes() {
        TravelProviderAllowList allowList = buildAllowList("https://maps.example.com", List.of("maps.example.com"));
        assertThatCode(() -> allowList.assertAllowed("https://maps.example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertAllowed: throws EgressBlockedException for disallowed host")
    void assertAllowed_throwsForDisallowedHost() {
        TravelProviderAllowList allowList = buildAllowList("https://maps.example.com", List.of("maps.example.com"));
        assertThatThrownBy(() -> allowList.assertAllowed("https://evil.attacker.com"))
                .isInstanceOf(TravelProviderAllowList.EgressBlockedException.class);
    }

    @Test
    @DisplayName("host matching is case-insensitive")
    void hostMatching_caseInsensitive() {
        assertThatCode(() -> buildAllowList("https://Maps.EXAMPLE.COM", List.of("maps.example.com")))
                .doesNotThrowAnyException();
    }

    private static TravelProviderAllowList buildAllowList(String baseUrl, List<String> allowedHosts) {
        TravelProviderProperties props = new TravelProviderProperties(
                new TravelProviderProperties.Provider(
                        baseUrl, "test-key", allowedHosts,
                        Duration.ofSeconds(2), Duration.ofSeconds(5), "car", 50.0),
                new TravelProviderProperties.Resilience(
                        Duration.ofMillis(1500), 2, 50f, 20, Duration.ofSeconds(30), 3),
                new TravelProviderProperties.Cache(300L, 4));
        return new TravelProviderAllowList(props);
    }
}
