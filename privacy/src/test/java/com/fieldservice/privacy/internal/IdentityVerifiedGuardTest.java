package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarEvent;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the identity-verified guard.
 * Guards must never fail open — unverified requests must always be refused.
 */
class IdentityVerifiedGuardTest {

    private final IdentityVerifiedGuard guard = new IdentityVerifiedGuard();

    @Test
    @DisplayName("Guard id is 'dsar.identity.verified'")
    void guardId_isCorrect() {
        assertThat(guard.guardId()).isEqualTo("dsar.identity.verified");
    }

    @Test
    @DisplayName("Guard refuses when identity_verified_at is null")
    void guard_refuses_whenNotVerified() {
        DsarRequestEntity request = unverifiedRequest();
        DsarGuardResult result = guard.evaluate(DsarState.VERIFIED, DsarEvent.CLAIM, request);
        assertThat(result).isInstanceOf(DsarGuardResult.Refused.class);
        DsarGuardResult.Refused refused = (DsarGuardResult.Refused) result;
        assertThat(refused.code()).isEqualTo("IDENTITY_NOT_VERIFIED");
    }

    @Test
    @DisplayName("Guard satisfied when identity_verified_at is set")
    void guard_satisfied_whenVerified() {
        DsarRequestEntity request = verifiedRequest();
        DsarGuardResult result = guard.evaluate(DsarState.VERIFIED, DsarEvent.CLAIM, request);
        assertThat(result).isInstanceOf(DsarGuardResult.Satisfied.class);
    }

    @Test
    @DisplayName("Guard also refuses FULFIL when not verified — never fail open")
    void guard_refuses_fulfil_whenNotVerified() {
        DsarRequestEntity request = unverifiedRequest();
        DsarGuardResult result = guard.evaluate(DsarState.IN_PROGRESS, DsarEvent.FULFIL, request);
        assertThat(result).isInstanceOf(DsarGuardResult.Refused.class);
    }

    private DsarRequestEntity unverifiedRequest() {
        return DsarRequestEntity.create(
                UUID.randomUUID(), DsarRequestType.ACCESS,
                "APP_USER", UUID.randomUUID(),
                Instant.now(), Instant.now().plusSeconds(86400 * 30), "test");
    }

    private DsarRequestEntity verifiedRequest() {
        DsarRequestEntity r = unverifiedRequest();
        r.applyTransition(DsarState.VERIFIED, DsarEvent.VERIFY_DIRECT,
                "EXTERNAL_MANUAL", null, Instant.now(), "test");
        return r;
    }
}
