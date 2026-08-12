package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.api.exception.BusinessGuardException;
import com.fieldservice.platform.api.exception.NotFoundException;
import com.fieldservice.privacy.api.ClassificationRegistry;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.FieldCorrection;
import com.fieldservice.privacy.api.FieldRectificationResult;
import com.fieldservice.privacy.api.SubjectDataRectifier;
import com.fieldservice.privacy.api.SubjectRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RectificationServiceTest {

    @Mock DsarRequestRepository requestRepository;
    @Mock ClassificationRegistry classificationRegistry;
    @Mock DomainEventPublisher eventPublisher;
    @Mock SubjectDataRectifier rectifier;
    @Mock Clock clock;
    @Mock ObjectMapper objectMapper;

    RectificationService service() {
        return new RectificationService(requestRepository, classificationRegistry,
                eventPublisher, List.of(rectifier), clock, objectMapper);
    }

    @Test
    void rectify_throwsNotFound_whenDsarMissing() {
        UUID id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().rectify(id, List.of(), null, "admin"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rectify_throwsBusinessGuard_whenDsarNotVerified() throws Exception {
        UUID id = UUID.randomUUID();
        DsarRequestEntity entity = buildEntity(id, DsarState.RECEIVED, DsarRequestType.RECTIFICATION);
        when(requestRepository.findById(id)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service().rectify(id, List.of(), null, "admin"))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("VERIFIED");
    }

    @Test
    void rectify_throwsBusinessGuard_whenFieldNotInClassificationRegistry() throws Exception {
        UUID id = UUID.randomUUID();
        DsarRequestEntity entity = buildEntity(id, DsarState.VERIFIED, DsarRequestType.RECTIFICATION);
        when(requestRepository.findById(id)).thenReturn(Optional.of(entity));
        when(classificationRegistry.findByEntityAndField("AppUser", "email")).thenReturn(Optional.empty());

        List<FieldCorrection> corrections = List.of(new FieldCorrection("AppUser", "email", "new@example.com"));
        assertThatThrownBy(() -> service().rectify(id, corrections, null, "admin"))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("not found in classification registry");
    }

    @Test
    void rectify_throwsBusinessGuard_whenFieldTierNotRectifiable() throws Exception {
        UUID id = UUID.randomUUID();
        DsarRequestEntity entity = buildEntity(id, DsarState.VERIFIED, DsarRequestType.RECTIFICATION);
        when(requestRepository.findById(id)).thenReturn(Optional.of(entity));
        when(classificationRegistry.findByEntityAndField("AppUser", "internalCode"))
                .thenReturn(Optional.of(buildClassificationView(ClassificationTier.INTERNAL)));

        List<FieldCorrection> corrections = List.of(new FieldCorrection("AppUser", "internalCode", "val"));
        assertThatThrownBy(() -> service().rectify(id, corrections, null, "admin"))
                .isInstanceOf(BusinessGuardException.class)
                .hasMessageContaining("not rectifiable");
    }

    @Test
    void rectify_appliesCorrections_throughRectifier() throws Exception {
        UUID id    = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        DsarRequestEntity entity = buildEntity(id, DsarState.VERIFIED, DsarRequestType.RECTIFICATION, subId);
        when(requestRepository.findById(id)).thenReturn(Optional.of(entity));
        when(classificationRegistry.findByEntityAndField("AppUser", "email"))
                .thenReturn(Optional.of(buildClassificationView(ClassificationTier.CONFIDENTIAL)));
        when(rectifier.supportedSubjectTypes()).thenReturn(Set.of("APP_USER"));
        when(rectifier.sectionName()).thenReturn("identity.app_user");
        when(rectifier.supportedEntityNames()).thenReturn(Set.of("AppUser"));
        when(rectifier.rectify(any(SubjectRef.class), any()))
                .thenReturn(List.of(FieldRectificationResult.applied("AppUser", "email", "rev-42")));
        when(clock.instant()).thenReturn(Instant.now());

        List<FieldCorrection> corrections = List.of(new FieldCorrection("AppUser", "email", "new@example.com"));
        RectificationService.RectificationOutcome outcome = service().rectify(id, corrections, null, "admin");

        assertThat(outcome.applied()).hasSize(1);
        assertThat(outcome.applied().get(0).fieldName()).isEqualTo("email");
        assertThat(outcome.skipped()).isEmpty();
        verify(rectifier).rectify(eq(new SubjectRef("APP_USER", subId)), any());
    }

    @Test
    void rectify_skips_whenNoRectifierSupportsEntityName() throws Exception {
        UUID id    = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        DsarRequestEntity entity = buildEntity(id, DsarState.VERIFIED, DsarRequestType.RECTIFICATION, subId);
        when(requestRepository.findById(id)).thenReturn(Optional.of(entity));
        when(classificationRegistry.findByEntityAndField("UnknownEntity", "field"))
                .thenReturn(Optional.of(buildClassificationView(ClassificationTier.CONFIDENTIAL)));
        when(rectifier.supportedSubjectTypes()).thenReturn(Set.of("APP_USER"));
        when(rectifier.supportedEntityNames()).thenReturn(Set.of("AppUser"));
        when(clock.instant()).thenReturn(Instant.now());

        List<FieldCorrection> corrections = List.of(new FieldCorrection("UnknownEntity", "field", "val"));
        RectificationService.RectificationOutcome outcome = service().rectify(id, corrections, null, "admin");

        assertThat(outcome.applied()).isEmpty();
        assertThat(outcome.skipped()).hasSize(1);
        assertThat(outcome.skipped().get(0).reason()).isEqualTo("NO_RECTIFIER_FOUND");
        verify(rectifier, never()).rectify(any(), any());
    }

    private static DsarRequestEntity buildEntity(UUID id, DsarState state, DsarRequestType type) throws Exception {
        return buildEntity(id, state, type, UUID.randomUUID());
    }

    private static DsarRequestEntity buildEntity(UUID id, DsarState state, DsarRequestType type, UUID subjectId) throws Exception {
        DsarRequestEntity e = new DsarRequestEntity();
        setField(e, "id", id);
        setField(e, "state", state);
        setField(e, "requestType", type);
        setField(e, "subjectType", "APP_USER");
        setField(e, "subjectId", subjectId);
        setField(e, "version", 0);
        return e;
    }

    private static ClassificationView buildClassificationView(ClassificationTier tier) {
        return new ClassificationView(UUID.randomUUID(), "identity", "AppUser", "email",
                tier, null, null, Instant.now(), "admin", Instant.now(), "admin", 1);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
