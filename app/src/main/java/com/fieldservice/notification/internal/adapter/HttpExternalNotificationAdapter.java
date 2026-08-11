package com.fieldservice.notification.internal.adapter;

import com.fieldservice.notification.api.NotificationRequest;
import com.fieldservice.notification.internal.PermanentNotificationException;
import com.fieldservice.notification.internal.RetryableNotificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP adapter for an external notification provider. Credentials are sourced from the
 * environment variable {@code NOTIFICATION_PROVIDER_API_KEY} and cached in an
 * {@link AtomicReference} for rotation support without restart.
 *
 * <p>Selection: {@code notification.provider=HTTP}.
 */
class HttpExternalNotificationAdapter implements ExternalNotificationAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalNotificationAdapter.class);

    private final RestClient restClient;
    private final AtomicReference<String> apiKey;

    HttpExternalNotificationAdapter(RestClient restClient) {
        this.restClient = restClient;
        String key = System.getenv("NOTIFICATION_PROVIDER_API_KEY");
        this.apiKey = new AtomicReference<>(key != null ? key : "");
        log.info("notification_http_adapter_init key_present={}", !this.apiKey.get().isBlank());
    }

    @Override
    public String send(NotificationRequest request) {
        Map<String, Object> body = Map.of(
                "channel",   request.channel().name(),
                "recipient", request.recipientContact(),
                "subject",   request.subject() != null ? request.subject() : "",
                "body",      request.body() != null ? request.body() : "",
                "eventRef",  request.eventId().toString()
        );

        try {
            String providerRef = restClient.post()
                    .uri("/v1/send")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.get())
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        int status = resp.getStatusCode().value();
                        if (status == 429) {
                            throw new RetryableNotificationException(
                                    "RATE_LIMITED", "Provider returned 429");
                        }
                        throw new PermanentNotificationException(
                                "CLIENT_ERROR_" + status,
                                "Provider returned permanent error: " + status);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        int status = resp.getStatusCode().value();
                        throw new RetryableNotificationException(
                                "SERVER_ERROR_" + status,
                                "Provider returned server error: " + status);
                    })
                    .body(String.class);

            return providerRef;

        } catch (RetryableNotificationException | PermanentNotificationException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new RetryableNotificationException("CONNECTION_ERROR", "Provider unreachable", e);
        } catch (Exception e) {
            throw new RetryableNotificationException("UNEXPECTED_ERROR", "Unexpected provider error", e);
        }
    }

    /** Refreshes the API key from the environment without a container restart. */
    void refreshApiKey() {
        String fresh = System.getenv("NOTIFICATION_PROVIDER_API_KEY");
        if (fresh != null) {
            apiKey.set(fresh);
            log.info("notification_http_adapter_key_refreshed key_present={}", !fresh.isBlank());
        }
    }

    @Override
    public String adapterName() { return "HTTP"; }
}
