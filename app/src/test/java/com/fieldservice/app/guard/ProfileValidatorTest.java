package com.fieldservice.app.guard;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProfileValidator}.
 */
class ProfileValidatorTest {

    @Test
    void passeswhenApiProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("api");
        ProfileValidator validator = new ProfileValidator(env);
        ContextRefreshedEvent event = rootContextEvent();

        assertThatNoException().isThrownBy(() -> validator.onApplicationEvent(event));
    }

    @Test
    void passesWhenWorkerProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("worker");
        ProfileValidator validator = new ProfileValidator(env);
        ContextRefreshedEvent event = rootContextEvent();

        assertThatNoException().isThrownBy(() -> validator.onApplicationEvent(event));
    }

    @Test
    void passesWhenBothProfilesAreActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("api", "worker");
        ProfileValidator validator = new ProfileValidator(env);
        ContextRefreshedEvent event = rootContextEvent();

        assertThatNoException().isThrownBy(() -> validator.onApplicationEvent(event));
    }

    @Test
    void throwsWhenNoDeploymentProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        // No api or worker profile
        ProfileValidator validator = new ProfileValidator(env);
        ContextRefreshedEvent event = rootContextEvent();

        assertThatThrownBy(() -> validator.onApplicationEvent(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No deployment profile active");
    }

    @Test
    void throwsWhenOnlyUnrecognisedProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("integration");
        ProfileValidator validator = new ProfileValidator(env);
        ContextRefreshedEvent event = rootContextEvent();

        assertThatThrownBy(() -> validator.onApplicationEvent(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No deployment profile active");
    }

    @Test
    void skipsValidationForChildContexts() {
        MockEnvironment env = new MockEnvironment();
        // No profiles set — would normally fail
        ProfileValidator validator = new ProfileValidator(env);

        // Simulate a child context (has a parent)
        org.springframework.context.ConfigurableApplicationContext parentCtx =
                mock(org.springframework.context.ConfigurableApplicationContext.class);
        org.springframework.context.ConfigurableApplicationContext childCtx =
                mock(org.springframework.context.ConfigurableApplicationContext.class);
        when(childCtx.getParent()).thenReturn(parentCtx);

        ContextRefreshedEvent childEvent = new ContextRefreshedEvent(childCtx);

        // Must not throw for child contexts
        assertThatNoException().isThrownBy(() -> validator.onApplicationEvent(childEvent));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ContextRefreshedEvent rootContextEvent() {
        org.springframework.context.ConfigurableApplicationContext ctx =
                mock(org.springframework.context.ConfigurableApplicationContext.class);
        when(ctx.getParent()).thenReturn(null);
        return new ContextRefreshedEvent(ctx);
    }
}
