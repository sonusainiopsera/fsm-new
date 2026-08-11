package com.fieldservice.notification.internal.adapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the notification module beans under the {@code worker} profile.
 * Adapter selection is driven by {@code notification.provider} (NONE | HTTP).
 */
@Configuration
@Profile("worker")
@EnableConfigurationProperties(NotificationProviderProperties.class)
class NotificationConfiguration {

    @Bean
    @ConditionalOnProperty(name = "notification.provider", havingValue = "NONE", matchIfMissing = true)
    ExternalNotificationAdapter stubExternalNotificationAdapter() {
        return new StubExternalNotificationAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "notification.provider", havingValue = "HTTP")
    ExternalNotificationAdapter httpExternalNotificationAdapter(
            NotificationProviderProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.connectTimeout().toMillis());
        factory.setReadTimeout((int) props.readTimeout().toMillis());
        RestClient restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
        return new HttpExternalNotificationAdapter(restClient);
    }
}
