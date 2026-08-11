package com.fieldservice.aigateway.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EgressAllowList} — AC-6 and Edge Cases.
 * No Spring context required.
 */
@DisplayName("EgressAllowList (AC-6)")
class EgressAllowListTest {

    @Test
    @DisplayName("empty allow-list refuses all calls")
    void empty_allow_list_refuses_all() {
        var sut = new EgressAllowList(List.of());
        assertThatThrownBy(() -> sut.validate(URI.create("https://api.openai.com/v1/chat")))
                .isInstanceOf(EgressAllowList.EgressRefusedException.class)
                .hasMessageContaining("allow-list is empty");
    }

    @Test
    @DisplayName("null allow-list treated as empty — refuses all")
    void null_allow_list_refuses_all() {
        var sut = new EgressAllowList(null);
        assertThatThrownBy(() -> sut.validate(URI.create("https://api.openai.com/v1/chat")))
                .isInstanceOf(EgressAllowList.EgressRefusedException.class);
    }

    @Test
    @DisplayName("host not in allow-list is refused")
    void host_not_in_allow_list_is_refused() {
        var sut = new EgressAllowList(List.of("api.openai.com"));
        assertThatThrownBy(() -> sut.validate(URI.create("https://evil.com/steal")))
                .isInstanceOf(EgressAllowList.EgressRefusedException.class)
                .hasMessageContaining("not in allow-list");
    }

    @Test
    @DisplayName("case-insensitive host matching")
    void host_matching_is_case_insensitive() {
        var sut = new EgressAllowList(List.of("API.OpenAI.com"));
        // DNS lookup will happen; we only test the allow-list case-matching step
        // by catching a non-allow-list error
        try {
            sut.validate(URI.create("https://api.openai.com/v1/chat"));
            // passed — fine if DNS resolves to public IP
        } catch (EgressAllowList.EgressRefusedException e) {
            org.assertj.core.api.Assertions.assertThat(e.getMessage())
                    .doesNotContain("not in allow-list"); // only DNS/private errors acceptable
        }
    }

    @Test
    @DisplayName("loopback 127.x.x.x IP is refused")
    void loopback_ip_refused() {
        var sut = new EgressAllowList(List.of("127.0.0.1"));
        assertThatThrownBy(() -> sut.validate(URI.create("http://127.0.0.1/v1")))
                .isInstanceOf(EgressAllowList.EgressRefusedException.class);
    }

    @Test
    @DisplayName("RFC-1918 private IP 192.168.x.x is refused")
    void rfc1918_ip_refused() {
        var sut = new EgressAllowList(List.of("192.168.1.1"));
        assertThatThrownBy(() -> sut.validate(URI.create("http://192.168.1.1/api")))
                .isInstanceOf(EgressAllowList.EgressRefusedException.class);
    }

    @Test
    @DisplayName("link-local 169.254.x.x (AWS metadata) is refused")
    void link_local_metadata_ip_refused() {
        var sut = new EgressAllowList(List.of("169.254.169.254"));
        assertThatThrownBy(() -> sut.validate(URI.create("http://169.254.169.254/latest")))
                .isInstanceOf(EgressAllowList.EgressRefusedException.class);
    }
}
