package com.fieldservice.app.audit;

import com.fieldservice.platform.audit.AppRevision;
import com.fieldservice.platform.audit.AppRevisionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AppRevisionListenerTest {

    private final AppRevisionListener listener = new AppRevisionListener();

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void populates_actorUserId_from_jwt_subject() {
        setJwtAuth("user-42", "ROLE_DISPATCHER");

        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getActorUserId()).isEqualTo("user-42");
    }

    @Test
    void populates_actorRole_stripped_of_prefix() {
        setJwtAuth("user-99", "ROLE_MANAGER");

        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getActorRole()).isEqualTo("MANAGER");
    }

    @Test
    void falls_back_to_SYSTEM_when_no_authentication() {
        // no auth set

        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getActorUserId()).isEqualTo(AppRevisionListener.SYSTEM_ACTOR);
        assertThat(rev.getActorRole()).isNull();
    }

    @Test
    void falls_back_to_SYSTEM_for_non_jwt_authentication() {
        var anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getActorUserId()).isEqualTo(AppRevisionListener.SYSTEM_ACTOR);
    }

    @Test
    void populates_traceId_from_MDC() {
        MDC.put("traceId", "trace-abc");

        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getTraceId()).isEqualTo("trace-abc");
    }

    @Test
    void populates_clientIp_from_MDC() {
        MDC.put("clientIp", "192.168.1.1");

        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getClientIp()).isEqualTo("192.168.1.1");
    }

    @Test
    void traceId_and_clientIp_null_when_MDC_empty() {
        AppRevision rev = new AppRevision();
        listener.newRevision(rev);

        assertThat(rev.getTraceId()).isNull();
        assertThat(rev.getClientIp()).isNull();
    }

    // ---- helpers ----

    private static void setJwtAuth(String subject, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("roles", List.of(roles))
                .build();
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }
}
