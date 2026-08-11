package com.fieldservice.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the notification provider API key from the environment variable
 * {@code NOTIFICATION_API_KEY}, resolved via Spring {@link Environment} so that
 * Kubernetes secrets injected as environment variables or mounted as properties files
 * are both supported.
 *
 * <p>The raw key is never committed, never logged, and never surfaced outside the worker logs.
 */
class EnvironmentNotificationSecrets implements NotificationProviderSecrets {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentNotificationSecrets.class);
    private static final String ENV_VAR = "NOTIFICATION_API_KEY";

    private final Environment environment;
    private final AtomicReference<String> cached = new AtomicReference<>();

    EnvironmentNotificationSecrets(Environment environment) {
        this.environment = environment;
        refresh();
    }

    @Override
    public String getApiKey() {
        String key = cached.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Notification provider credential is not configured; set " + ENV_VAR);
        }
        return key;
    }

    @Override
    public void refresh() {
        String value = environment.getProperty(ENV_VAR);
        if (value == null || value.isBlank()) {
            log.warn("notification_provider credential not found; variable={}", ENV_VAR);
            cached.set("");
        } else {
            cached.set(value);
            log.info("notification_provider credential loaded; variable={}", ENV_VAR);
        }
    }
}
