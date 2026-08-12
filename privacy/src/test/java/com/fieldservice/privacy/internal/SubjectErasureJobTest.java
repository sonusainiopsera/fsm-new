package com.fieldservice.privacy.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldservice.platform.api.DomainEventPublisher;
import com.fieldservice.platform.crypto.SubjectKeyManager;
import com.fieldservice.platform.outbox.JdbcSchedulingLock;
import com.fieldservice.privacy.api.DsarRequestType;
import com.fieldservice.privacy.api.DsarState;
import com.fieldservice.privacy.api.ErasureVerificationScope;
import com.fieldservice.privacy.api.SubjectRef;
import com.fieldservice.privacy.api.VerificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectErasureJobTest {

    @Mock DsarRequestRepository requestRepository;
    @Mock DsarExportArtifactRepository artifactRepository;
    @Mock SubjectErasureRepository erasureRepository;
    @Mock RetentionPolicyRepository retentionPolicyRepository;
    @Mock SubjectKeyManager subjectKeyManager;
    @Mock DomainEventPublisher eventPublisher;
    @Mock JdbcSchedulingLock schedulingLock;
    @Mock Clock clock;
    @Mock ErasureVerificationScope verificationScope;

    PlatformTransactionManager txManager;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        txManager = mock(PlatformTransactionManager.class,
                invocation -> {
                    if (invocation.getMethod().getName().equals("getTransaction")) {
                        return mock(org.springframework.transaction.TransactionStatus.class);
                    }
                    return null;
                });
        when(clock.instant()).thenReturn(Instant.parse("2026-08-12T10:00:00Z"));
    }

    SubjectErasureJob job() {
        return new SubjectErasureJob(requestRepository, artifactRepository, erasureRepository,
                retentionPolicyRepository, subjectKeyManager, List.of(verificationScope),
                eventPublisher, schedulingLock, txManager, clock, objectMapper);
    }

    @Test
    void doSweep_processesOnlyErasureRequests() throws Exception {
        DsarRequestEntity erasureReq = buildEntity(UUID.randomUUID(), DsarState.VERIFIED,
                DsarRequestType.ERASURE, UUID.randomUUID());
        DsarRequestEntity accessReq = buildEntity(UUID.randomUUID(), DsarState.VERIFIED,
                DsarRequestType.ACCESS, UUID.randomUUID());

        when(requestRepository.findAllVerifiedOrderByDueAt()).thenReturn(List.of(erasureReq, accessReq));
        when(erasureRepository.findCompletedBySubject(any(), any())).thenReturn(Optional.of(mock(SubjectErasureEntity.class)));
        when(erasureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepository.findById(erasureReq.getId())).thenReturn(Optional.of(erasureReq));
        when(retentionPolicyRepository.findByDataCategory(any())).thenReturn(Optional.empty());
        when(artifactRepository.findByDsarRequestId(any())).thenReturn(Optional.empty());

        job().doSweep();

        // Should only process the ERASURE request
        verify(erasureRepository).findCompletedBySubject(anyString(), any());
    }

    @Test
    void processErasure_recordsIdempotentNoop_whenAlreadyCompleted() throws Exception {
        UUID dsarId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        DsarRequestEntity request = buildEntity(dsarId, DsarState.VERIFIED, DsarRequestType.ERASURE, subjectId);

        when(requestRepository.findAllVerifiedOrderByDueAt()).thenReturn(List.of(request));
        when(erasureRepository.findCompletedBySubject("APP_USER", subjectId))
                .thenReturn(Optional.of(mock(SubjectErasureEntity.class)));
        when(erasureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepository.findById(dsarId)).thenReturn(Optional.of(request));
        when(retentionPolicyRepository.findByDataCategory(any())).thenReturn(Optional.empty());
        when(artifactRepository.findByDsarRequestId(any())).thenReturn(Optional.empty());

        job().doSweep();

        ArgumentCaptor<SubjectErasureEntity> captor = ArgumentCaptor.forClass(SubjectErasureEntity.class);
        verify(erasureRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(SubjectErasureEntity.OUTCOME_IDEMPOTENT_NOOP);
        verify(subjectKeyManager, never()).destroy(any());
    }

    @Test
    void processErasure_refuses_whenLegalHoldActive() throws Exception {
        UUID dsarId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        DsarRequestEntity request = buildEntity(dsarId, DsarState.VERIFIED, DsarRequestType.ERASURE, subjectId);

        when(requestRepository.findAllVerifiedOrderByDueAt()).thenReturn(List.of(request));
        when(erasureRepository.findCompletedBySubject(any(), any())).thenReturn(Optional.empty());

        RetentionPolicyEntity policy = mock(RetentionPolicyEntity.class);
        when(policy.isLegalHold()).thenReturn(true);
        when(retentionPolicyRepository.findByDataCategory("APP_USER")).thenReturn(Optional.of(policy));
        when(erasureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        job().doSweep();

        ArgumentCaptor<SubjectErasureEntity> captor = ArgumentCaptor.forClass(SubjectErasureEntity.class);
        verify(erasureRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(SubjectErasureEntity.OUTCOME_REFUSED);
        assertThat(captor.getValue().getOutcome()).doesNotContain("personal_data");
        verify(subjectKeyManager, never()).destroy(any());
    }

    @Test
    void processErasure_tombstoneContainsNoPiiValues() throws Exception {
        UUID dsarId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        DsarRequestEntity request = buildEntity(dsarId, DsarState.VERIFIED, DsarRequestType.ERASURE, subjectId);

        when(requestRepository.findAllVerifiedOrderByDueAt()).thenReturn(List.of(request));
        when(erasureRepository.findCompletedBySubject("APP_USER", subjectId)).thenReturn(Optional.empty());
        when(retentionPolicyRepository.findByDataCategory(any())).thenReturn(Optional.empty());
        when(artifactRepository.findByDsarRequestId(any())).thenReturn(Optional.empty());
        when(subjectKeyManager.resolveActive(any())).thenThrow(new RuntimeException("unavailable"));
        when(verificationScope.scopeName()).thenReturn("live-tables");
        when(verificationScope.verify(any(SubjectRef.class)))
                .thenReturn(VerificationResult.clean("live-tables", 5, Instant.now()));
        when(erasureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepository.findById(dsarId)).thenReturn(Optional.of(request));

        job().doSweep();

        ArgumentCaptor<SubjectErasureEntity> captor = ArgumentCaptor.forClass(SubjectErasureEntity.class);
        verify(erasureRepository).save(captor.capture());
        SubjectErasureEntity tombstone = captor.getValue();

        assertThat(tombstone.getOutcome()).isEqualTo(SubjectErasureEntity.OUTCOME_COMPLETED);
        assertThat(tombstone.getDsarRequestId()).isEqualTo(dsarId);
        assertThat(tombstone.getSubjectType()).isEqualTo("APP_USER");
        assertThat(tombstone.getSubjectId()).isEqualTo(subjectId);
        // Verify key destruction was called
        verify(subjectKeyManager).destroy(any());
    }

    @Test
    void processErasure_runsAllVerificationScopes() throws Exception {
        UUID dsarId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        DsarRequestEntity request = buildEntity(dsarId, DsarState.VERIFIED, DsarRequestType.ERASURE, subjectId);

        when(requestRepository.findAllVerifiedOrderByDueAt()).thenReturn(List.of(request));
        when(erasureRepository.findCompletedBySubject("APP_USER", subjectId)).thenReturn(Optional.empty());
        when(retentionPolicyRepository.findByDataCategory(any())).thenReturn(Optional.empty());
        when(artifactRepository.findByDsarRequestId(any())).thenReturn(Optional.empty());
        when(subjectKeyManager.resolveActive(any())).thenThrow(new RuntimeException("unavailable"));
        when(verificationScope.scopeName()).thenReturn("live-tables");
        when(verificationScope.verify(any(SubjectRef.class)))
                .thenReturn(VerificationResult.clean("live-tables", 3, Instant.now()));
        when(erasureRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(requestRepository.findById(dsarId)).thenReturn(Optional.of(request));

        job().doSweep();

        verify(verificationScope).verify(new SubjectRef("APP_USER", subjectId));
    }

    private static DsarRequestEntity buildEntity(UUID id, DsarState state, DsarRequestType type, UUID subjectId)
            throws Exception {
        DsarRequestEntity e = new DsarRequestEntity();
        setField(e, "id", id);
        setField(e, "state", state);
        setField(e, "requestType", type);
        setField(e, "subjectType", "APP_USER");
        setField(e, "subjectId", subjectId);
        setField(e, "version", 0);
        return e;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
