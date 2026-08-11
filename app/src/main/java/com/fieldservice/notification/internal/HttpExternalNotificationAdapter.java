package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * HTTP adapter that forwards notification sends to an external provider.
 *
 * <p>POST {baseUrl}/send with JSON body; expects 200 with providerReference in JSON response.
 *
 * <p>Credentials are resolved at call-time from {@link NotificationProviderSecrets}; the raw
 * key is never stored in any field, never logged, and never surfaced outside worker logs.
 */
class HttpExternalNotificationAdapter implements ExternalNotificationAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalNotificationAdapter.class);

    private final RestClient restClient;
    private final NotificationProviderSecrets secrets;
    private final String baseUrl;

    HttpExternalNotificationAdapter(RestClient restClient,
                                     NotificationProviderSecrets secrets,
                                     String baseUrl) {
        this.restClient = restClient;
        this.secrets = secrets;
        this.baseUrl = baseUrl;
    }

    @Override
    public String adapterName() {
        return "http";
    }

    @Override
    public String send(NotificationRequest request, String maskedRecipient) throws Exception {
        var body = Map.of(
                "eventId", request.eventId().toString(),
                "channel", request.channel().name(),
                "recipientMask", maskedRecipient,
                "idempotencyKey", request.idempotencyKey() != null ? request.idempotencyKey() : request.eventId().toString(),
                "category", nvl(request.category()),
                "title", nvl(request.title()),
                "body", nvl(request.body()),
                "severity", nvl(request.severity())
        );

        log.debug("notification_http send channel={} eventId={} baseUrl={}",
                request.channel(), request.eventId(), sanitize(baseUrl));

        try {
            var response = restClient.post()
                    .uri("/send")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + secrets.getApiKey())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 429) {
                            throw new ProviderTransientException("rate_limited status=429");
                        }
                        throw new ProviderPermanentException("provider_rejected status=" + status);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ProviderTransientException("provider_error status=" + res.getStatusCode().value());
                    })
                    .toEntity(Map.class);

            if (response.getBody() != null && response.getBody().containsKey("providerReference")) {
                return String.valueOf(response.getBody().get("providerReference"));
            }
            return null;
        } catch (ProviderTransientException | ProviderPermanentException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderTransientException("provider_io_error", e);
        }
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static String sanitize(String url) {
        if (url == null) return "null";
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getScheme() + "://" + u.getHost();
        } catch (Exception e) {
            return "<invalid>";
        }
    }
}
