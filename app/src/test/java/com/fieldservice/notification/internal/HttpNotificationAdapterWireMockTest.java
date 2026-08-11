package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.NotificationRequest;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock integration tests for {@link HttpExternalNotificationAdapter} — no Spring context (WO-195).
 */
@WireMockTest
@DisplayName("HTTP notification adapter WireMock tests")
class HttpNotificationAdapterWireMockTest {

    private HttpExternalNotificationAdapter adapter;

    @BeforeEach
    void buildAdapter(WireMockRuntimeInfo wm) {
        String baseUrl = wm.getHttpBaseUrl();

        NotificationProviderSecrets testSecrets = new NotificationProviderSecrets() {
            @Override public String getApiKey() { return "test-api-key"; }
            @Override public void refresh() {}
        };

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));

        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        adapter = new HttpExternalNotificationAdapter(restClient, testSecrets, baseUrl);
    }

    private NotificationRequest makeRequest() {
        return new NotificationRequest(
                UUID.randomUUID(), NotificationChannel.EMAIL,
                UUID.randomUUID(), "m***@example.com",
                "idem-" + UUID.randomUUID(),
                "TEST", "Test title", "Test body", "INFO");
    }

    @Test
    @DisplayName("Provider 200 OK → returns providerReference")
    void success_200_returnsProviderReference() throws Exception {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(okJson("""
                        {"providerReference": "prov-ref-abc123", "status": "accepted"}
                        """)));

        String ref = adapter.send(makeRequest(), "m***@example.com");

        assertThat(ref).isEqualTo("prov-ref-abc123");
    }

    @Test
    @DisplayName("Provider 200 OK with no providerReference → returns null")
    void success_200_noRef_returnsNull() throws Exception {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(okJson("""
                        {"status": "accepted"}
                        """)));

        String ref = adapter.send(makeRequest(), "m***@example.com");

        assertThat(ref).isNull();
    }

    @Test
    @DisplayName("Provider 429 → throws ProviderTransientException")
    void provider429_throwsTransient() {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "60")
                        .withBody("{\"error\":\"rate limited\"}")));

        assertThatThrownBy(() -> adapter.send(makeRequest(), "m***@example.com"))
                .isInstanceOf(ProviderTransientException.class)
                .hasMessageContaining("rate_limited");
    }

    @Test
    @DisplayName("Provider 500 → throws ProviderTransientException")
    void provider500_throwsTransient() {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"internal server error\"}")));

        assertThatThrownBy(() -> adapter.send(makeRequest(), "m***@example.com"))
                .isInstanceOf(ProviderTransientException.class)
                .hasMessageContaining("provider_error");
    }

    @Test
    @DisplayName("Provider 401 → throws ProviderPermanentException")
    void provider401_throwsPermanent() {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"unauthorized\"}")));

        assertThatThrownBy(() -> adapter.send(makeRequest(), "m***@example.com"))
                .isInstanceOf(ProviderPermanentException.class);
    }

    @Test
    @DisplayName("Provider 400 → throws ProviderPermanentException")
    void provider400_throwsPermanent() {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid recipient\"}")));

        assertThatThrownBy(() -> adapter.send(makeRequest(), "m***@example.com"))
                .isInstanceOf(ProviderPermanentException.class);
    }

    @Test
    @DisplayName("Request carries Authorization: Bearer header")
    void request_hasAuthorizationHeader() throws Exception {
        stubFor(post(urlEqualTo("/send"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .willReturn(okJson("{\"providerReference\":\"ok\"}")));

        adapter.send(makeRequest(), "m***@example.com");

        verify(postRequestedFor(urlEqualTo("/send"))
                .withHeader("Authorization", equalTo("Bearer test-api-key")));
    }

    @Test
    @DisplayName("Request body does not contain raw recipient contact — only mask")
    void request_doesNotLeakRawContact() throws Exception {
        stubFor(post(urlEqualTo("/send"))
                .willReturn(okJson("{\"providerReference\":\"ok\"}")));

        var request = new NotificationRequest(
                UUID.randomUUID(), NotificationChannel.EMAIL,
                UUID.randomUUID(), "real.secret@company.com",
                "idem", "TEST", "Title", "Body", "INFO");

        adapter.send(request, "r***@company.com");

        verify(postRequestedFor(urlEqualTo("/send"))
                .withRequestBody(containing("r***@company.com"))
                .withRequestBody(notContaining("real.secret@company.com")));
    }
}
