package com.fieldservice.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.privacy.api.ErasureVerificationScope;
import com.fieldservice.privacy.api.ErasureView;
import com.fieldservice.privacy.api.InitiateErasureRequest;
import com.fieldservice.privacy.api.SubjectDataProvider;
import com.fieldservice.privacy.api.SubjectDataSection;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.internal.DsarExportArtifactRepository;
import com.fieldservice.privacy.internal.DsarRequest;
import com.fieldservice.privacy.internal.DsarRequestRepository;
import com.fieldservice.privacy.internal.DsarState;
import com.fieldservice.privacy.internal.RetentionPolicy;
import com.fieldservice.privacy.internal.RetentionPolicyRepository;
import com.fieldservice.privacy.internal.SubjectErasure;
import com.fieldservice.privacy.internal.SubjectErasureJob;
import com.fieldservice.privacy.internal.SubjectErasureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubjectErasureJob} — no Spring context.
 */
class SubjectErasureJobTest {

    private DsarRequestRepository dsarRepository;
    private SubjectErasureRepository erasureRepository;
    private DsarExportArtifactRepository artifactRepository;
    private SubjectKeyManager keyManager;
    private RetentionPolicyRepository retentionPolicyRepository;
    private SubjectErasureJob job;

    private static final UUID SUBJECT_ID   = UUID.fromString("dd000000-0000-0000-0000-000000000001");
    private static final UUID DSAR_ID      = UUID.fromString("dd000000-0000-7000-8000-000000000001");
    private static final String SUBJECT_TYPE = "CUSTOMER";

    @BeforeEach
    void setUp() {
        dsarRepository          = mock(DsarRequestRepository.class);
        erasureRepository       = mock(SubjectErasureRepository.class);
        artifactRepository      = mock(DsarExportArtifactRepository.class);
        keyManager              = mock(SubjectKeyManager.class);
        retentionPolicyRepository = mock(RetentionPolicyRepository.class);

        when(retentionPolicyRepository.findAll()).thenReturn(List.of());
        when(artifactRepository.findByDsarRequestId(any())).thenReturn(Optional.empty());
        when(keyManager.currentVersion(any(), any())).thenReturn(1);
        when(erasureRepository.findBySubjectTypeAndSubjectIdAndOutcome(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(erasureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job = new SubjectErasureJob(
                dsarRepository, erasureRepository, artifactRepository,
                keyManager, List.of(), List.of(),
                retentionPolicyRepository,
                new ObjectMapper(), Clock.systemUTC());
    }

    // ── Guard: wrong confirmation token ────────────────────────────────────

    @Test
    void initiateErasure_wrongConfirmation_throws422() {
        var req = new InitiateErasureRequest(DSAR_ID, "WRONG", null);
        assertThatThrownBy(() -> job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("CONFIRM_ERASURE");
    }

    // ── Guard: DSAR not found ───────────────────────────────────────────────

    @Test
    void initiateErasure_dsarNotFound_throws404() {
        when(dsarRepository.findById(DSAR_ID)).thenReturn(Optional.empty());
        var req = new InitiateErasureRequest(DSAR_ID, "CONFIRM_ERASURE", null);
        assertThatThrownBy(() -> job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(NotFoundException.class);
    }

    // ── Guard: unverified DSAR ──────────────────────────────────────────────

    @Test
    void initiateErasure_unverifiedDsar_throws422() {
        stubDsarWithState(DsarState.RECEIVED);
        var req = new InitiateErasureRequest(DSAR_ID, "CONFIRM_ERASURE", null);
        assertThatThrownBy(() -> job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("VERIFIED");
    }

    // ── Idempotency ─────────────────────────────────────────────────────────

    @Test
    void initiateErasure_alreadyErased_returnsIdempotentView() {
        stubDsarWithState(DsarState.VERIFIED);
        SubjectErasure existing = new SubjectErasure(
                DSAR_ID, SUBJECT_TYPE, SUBJECT_ID, "key-ref",
                Instant.now(), "actor", "[]", "[]", "COMPLETED", null);
        when(erasureRepository.findBySubjectTypeAndSubjectIdAndOutcome(
                SUBJECT_TYPE, SUBJECT_ID, "COMPLETED"))
                .thenReturn(Optional.of(existing));

        var req = new InitiateErasureRequest(DSAR_ID, "CONFIRM_ERASURE", null);
        ErasureView view = job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req);

        assertThat(view.outcome()).isEqualTo("COMPLETED");
        // Key destruction must NOT be called again
        verify(keyManager, never()).destroy(any(), any());
    }

    // ── Guard: legal hold ──────────────────────────────────────────────────

    @Test
    void initiateErasure_legalHold_throws422() {
        stubDsarWithState(DsarState.VERIFIED);
        RetentionPolicy legalHoldPolicy = mock(RetentionPolicy.class);
        when(legalHoldPolicy.isLegalHold()).thenReturn(true);
        when(legalHoldPolicy.isEnabled()).thenReturn(true);
        when(legalHoldPolicy.isRatified()).thenReturn(true);
        when(retentionPolicyRepository.findAll()).thenReturn(List.of(legalHoldPolicy));

        var req = new InitiateErasureRequest(DSAR_ID, "CONFIRM_ERASURE", null);
        assertThatThrownBy(() -> job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("LEGAL_HOLD");

        verify(keyManager, never()).destroy(any(), any());
    }

    // ── Happy path: key destruction called ─────────────────────────────────

    @Test
    void initiateErasure_happy_destroysKeyAndPersistsTombstone() {
        stubDsarWithState(DsarState.VERIFIED);
        var req = new InitiateErasureRequest(DSAR_ID, "CONFIRM_ERASURE", null);

        ErasureView view = job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req);

        verify(keyManager).destroy(SUBJECT_TYPE, SUBJECT_ID);
        verify(erasureRepository).save(any(SubjectErasure.class));
        assertThat(view.outcome()).isEqualTo("COMPLETED");
        assertThat(view.subjectType()).isEqualTo(SUBJECT_TYPE);
        assertThat(view.subjectId()).isEqualTo(SUBJECT_ID);
    }

    // ── Tombstone contains no PII ─────────────────────────────────────────

    @Test
    void tombstone_containsNoPiiValues() {
        stubDsarWithState(DsarState.VERIFIED);
        var req = new InitiateErasureRequest(DSAR_ID, "CONFIRM_ERASURE", null);

        ErasureView view = job.initiateErasure(SUBJECT_TYPE, SUBJECT_ID, req);
        String serialized = view.toString();

        // Tombstone must not contain personal data values
        assertThat(serialized).doesNotContain("Fixture Corp");
        assertThat(serialized).doesNotContain("test.person@example.com");
        assertThat(serialized).doesNotContain("+15555550400");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubDsarWithState(DsarState state) {
        DsarRequest dsar = Mockito.mock(DsarRequest.class);
        when(dsar.getId()).thenReturn(DSAR_ID);
        when(dsar.getState()).thenReturn(state);
        when(dsar.getSubjectType()).thenReturn(SUBJECT_TYPE);
        when(dsar.getSubjectId()).thenReturn(SUBJECT_ID);
        when(dsarRepository.findById(DSAR_ID)).thenReturn(Optional.of(dsar));
    }
}
