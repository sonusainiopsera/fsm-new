package com.fieldservice.app.audit;

import com.fieldservice.platform.audit.AppRevisionEntity;
import com.fieldservice.platform.audit.AppRevisionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link AppRevisionListener} actor-population logic.
 * Covers AC-9: RevisionListener actor population including the unauthenticated worker fallback.
 */
@DisplayName("AppRevisionListener unit tests")
class RevisionListenerUnitTest {

    private final AppRevisionListener listener = new AppRevisionListener();

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MDC.put("traceId", "test-trace-id-001");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    @Test
    @DisplayName("Authenticated principal populates actor_user_id and actor_role")
    void authenticated_principal_populates_actor_fields() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "test-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER"))));

        AppRevisionEntity rev = new AppRevisionEntity();
        listener.newRevision(rev);

        assertThat(rev.getActorUserId()).isEqualTo("test-user");
        assertThat(rev.getActorRole()).isEqualTo("ROLE_DISPATCHER");
        assertThat(rev.getTraceId()).isEqualTo("test-trace-id-001");
        assertThat(rev.getClientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("No authentication falls back to synthetic 'system' actor")
    void no_authentication_falls_back_to_system_actor() {
        SecurityContextHolder.clearContext();  // no principal

        AppRevisionEntity rev = new AppRevisionEntity();
        listener.newRevision(rev);

        assertThat(rev.getActorUserId()).isEqualTo("system");
        assertThat(rev.getActorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Anonymous authentication falls back to 'system' actor")
    void anonymous_authentication_falls_back_to_system_actor() {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        AppRevisionEntity rev = new AppRevisionEntity();
        listener.newRevision(rev);

        assertThat(rev.getActorUserId()).isEqualTo("system");
        assertThat(rev.getActorRole()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("Missing traceId in MDC results in null traceId (not an error)")
    void missing_trace_id_results_in_null() {
        MDC.remove("traceId");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "user", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        AppRevisionEntity rev = new AppRevisionEntity();
        listener.newRevision(rev);

        assertThat(rev.getTraceId()).isNull();
    }

    @Test
    @DisplayName("No request context results in null clientIp (not an error)")
    void no_request_context_results_in_null_client_ip() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "worker", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        AppRevisionEntity rev = new AppRevisionEntity();

        // Must not throw
        assertThatCode(() -> listener.newRevision(rev)).doesNotThrowAnyException();
        assertThat(rev.getClientIp()).isNull();
    }
}
