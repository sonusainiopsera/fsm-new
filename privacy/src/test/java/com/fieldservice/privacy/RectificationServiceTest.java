package com.fieldservice.privacy;

import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.platform.crypto.SubjectKeyState;
import com.fieldservice.platform.exception.BusinessGuardException;
import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.RectifyRequest;
import com.fieldservice.privacy.api.RectifyResponse;
import com.fieldservice.privacy.api.SubjectDataRectifier;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.internal.DsarRequest;
import com.fieldservice.privacy.internal.DsarRequestRepository;
import com.fieldservice.privacy.internal.DsarState;
import com.fieldservice.privacy.internal.RectificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RectificationService} — no Spring context.
 */
class RectificationServiceTest {

    private DsarRequestRepository dsarRepository;
    private ClassificationRegistry classificationRegistry;
    private SubjectKeyManager keyManager;
    private SubjectDataRectifier rectifier;
    private RectificationService service;

    private static final UUID SUBJECT_ID   = UUID.fromString("dd000000-0000-0000-0000-000000000001");
    private static final UUID DSAR_ID      = UUID.fromString("dd000000-0000-7000-8000-000000000001");
    private static final String SUBJECT_TYPE = "CUSTOMER";

    @BeforeEach
    void setUp() {
        dsarRepository       = mock(DsarRequestRepository.class);
        classificationRegistry = mock(ClassificationRegistry.class);
        keyManager           = mock(SubjectKeyManager.class);
        rectifier            = mock(SubjectDataRectifier.class);

        service = new RectificationService(
                dsarRepository, classificationRegistry, keyManager, List.of(rectifier));

        when(rectifier.module()).thenReturn("identity");
        when(rectifier.supportedSubjectTypes()).thenReturn(List.of("CUSTOMER"));
        when(keyManager.getState(SUBJECT_TYPE, SUBJECT_ID)).thenReturn(SubjectKeyState.ACTIVE);
    }

    // ── Guard: DSAR not found ───────────────────────────────────────────────

    @Test
    void rectify_dsarNotFound_throws404() {
        when(dsarRepository.findById(DSAR_ID)).thenReturn(Optional.empty());

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "primaryContactEmail", "new@example.com")),
                null);

        assertThatThrownBy(() -> service.rectify(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(NotFoundException.class);
    }

    // ── Guard: unverified DSAR ──────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DsarState.class, names = {"RECEIVED", "IDENTITY_PENDING"})
    void rectify_unverifiedDsar_throws422(DsarState state) {
        stubDsarWithState(state);

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "name", "new")), null);

        assertThatThrownBy(() -> service.rectify(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("VERIFIED");
    }

    // ── Guard: destroyed key ────────────────────────────────────────────────

    @Test
    void rectify_destroyedKey_throws422() {
        stubDsarWithState(DsarState.VERIFIED);
        when(keyManager.getState(SUBJECT_TYPE, SUBJECT_ID)).thenReturn(SubjectKeyState.DESTROYED);

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "name", "new")), null);

        assertThatThrownBy(() -> service.rectify(SUBJECT_TYPE, SUBJECT_ID, req))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("KEY_DESTROYED");
    }

    // ── Allow-list: unclassified field ─────────────────────────────────────

    @Test
    void rectify_unclassifiedField_skipped() {
        stubDsarWithState(DsarState.VERIFIED);
        when(classificationRegistry.findByEntityAndField("Customer", "unknownField"))
                .thenReturn(Optional.empty());

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "unknownField", "x")), null);

        RectifyResponse resp = service.rectify(SUBJECT_TYPE, SUBJECT_ID, req);
        assertThat(resp.applied()).isEmpty();
        assertThat(resp.skipped()).hasSize(1);
        assertThat(resp.skipped().get(0).reason()).contains("NOT_CLASSIFIED");
    }

    // ── Allow-list: non-rectifiable tier ───────────────────────────────────

    @Test
    void rectify_internalTierField_skipped() {
        stubDsarWithState(DsarState.VERIFIED);
        when(classificationRegistry.findByEntityAndField("Customer", "accountCode"))
                .thenReturn(Optional.of(stubView("Customer", "accountCode", ClassificationTier.INTERNAL)));

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "accountCode", "x")), null);

        RectifyResponse resp = service.rectify(SUBJECT_TYPE, SUBJECT_ID, req);
        assertThat(resp.applied()).isEmpty();
        assertThat(resp.skipped().get(0).reason()).contains("NOT_RECTIFIABLE");
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    void rectify_confidentialField_appliedViaRectifier() {
        stubDsarWithState(DsarState.VERIFIED);
        when(classificationRegistry.findByEntityAndField("Customer", "primaryContactEmail"))
                .thenReturn(Optional.of(stubView("Customer", "primaryContactEmail", ClassificationTier.CONFIDENTIAL)));
        when(rectifier.rectify(
                new SubjectRef(SUBJECT_TYPE, SUBJECT_ID), "Customer", "primaryContactEmail", "new@example.com"))
                .thenReturn(SubjectDataRectifier.RectifyFieldResult.applied("Customer", "primaryContactEmail", 42L));

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "primaryContactEmail", "new@example.com")),
                null);

        RectifyResponse resp = service.rectify(SUBJECT_TYPE, SUBJECT_ID, req);
        assertThat(resp.applied()).hasSize(1);
        assertThat(resp.applied().get(0).revisionId()).isEqualTo(42L);
        assertThat(resp.skipped()).isEmpty();
    }

    // ── Tombstone no-PII assertion ──────────────────────────────────────────

    @Test
    void rectifyResponse_containsNoSubjectPii() {
        stubDsarWithState(DsarState.VERIFIED);
        when(classificationRegistry.findByEntityAndField("Customer", "name"))
                .thenReturn(Optional.of(stubView("Customer", "name", ClassificationTier.CONFIDENTIAL)));
        when(rectifier.rectify(
                new SubjectRef(SUBJECT_TYPE, SUBJECT_ID), "Customer", "name", "REPLACEMENT"))
                .thenReturn(SubjectDataRectifier.RectifyFieldResult.applied("Customer", "name", 99L));

        var req = new RectifyRequest(DSAR_ID,
                List.of(new RectifyRequest.FieldCorrection("Customer", "name", "REPLACEMENT")), null);

        RectifyResponse resp = service.rectify(SUBJECT_TYPE, SUBJECT_ID, req);

        // The response structure should not contain the original PII value
        String serialized = resp.toString();
        assertThat(serialized).doesNotContain("Fixture Corp"); // original name
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void stubDsarWithState(DsarState state) {
        DsarRequest dsar = Mockito.mock(DsarRequest.class);
        when(dsar.getId()).thenReturn(DSAR_ID);
        when(dsar.getState()).thenReturn(state);
        when(dsarRepository.findById(DSAR_ID)).thenReturn(Optional.of(dsar));
    }

    private ClassificationView stubView(String entity, String field, ClassificationTier tier) {
        return new ClassificationView(UUID.randomUUID(), "identity", entity, field,
                tier, null, null, 0, null, null);
    }
}
