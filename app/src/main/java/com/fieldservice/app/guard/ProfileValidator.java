package com.fieldservice.app.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * Fails fast when neither {@code api} nor {@code worker} profile is active.
 *
 * <p>An ambiguous half-configured start is worse than a clear startup error.
 * This guard enforces the architecture constraint that every deployed instance
 * declares its role explicitly.</p>
 *
 * <p>Both profiles may be simultaneously active for local development.</p>
 */
@Component
public class ProfileValidator implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProfileValidator.class);

    static final Set<String> DEPLOYMENT_PROFILES = Set.of("api", "worker");

    private final Environment environment;

    public ProfileValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Only validate on the root context, not child contexts (e.g. embedded servers)
        if (event.getApplicationContext().getParent() != null) {
            return;
        }

        Set<String> activeProfiles = Set.of(environment.getActiveProfiles());
        boolean hasDeploymentProfile = activeProfiles.stream()
                .anyMatch(DEPLOYMENT_PROFILES::contains);

        if (!hasDeploymentProfile) {
            String message = String.format(
                    "No deployment profile active. At least one of %s must be set. "
                    + "Active profiles: %s. "
                    + "Start with: java -Dspring.profiles.active=api -jar app.jar "
                    + "or -Dspring.profiles.active=worker",
                    DEPLOYMENT_PROFILES,
                    Arrays.toString(environment.getActiveProfiles())
            );
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("Deployment profiles validated: active={}", activeProfiles);
    }
}
