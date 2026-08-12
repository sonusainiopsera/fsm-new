package com.fieldservice.privacy.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/**
 * Append-only repository for subject erasure tombstones.
 *
 * <p>No delete or modifying methods are exposed — the subject_erasure table is
 * strictly append-only, enforced both here and by the partial unique index on
 * (subject_type, subject_id) WHERE outcome = 'COMPLETED'.
 */
interface SubjectErasureRepository extends JpaRepository<SubjectErasureEntity, UUID> {

    @Query("SELECT e FROM SubjectErasureEntity e " +
           "WHERE e.subjectType = :subjectType AND e.subjectId = :subjectId " +
           "AND e.outcome = 'COMPLETED'")
    Optional<SubjectErasureEntity> findCompletedBySubject(String subjectType, UUID subjectId);

    @Query("SELECT e FROM SubjectErasureEntity e " +
           "WHERE e.subjectType = :subjectType AND e.subjectId = :subjectId " +
           "ORDER BY e.erasedAt DESC")
    java.util.List<SubjectErasureEntity> findAllBySubject(String subjectType, UUID subjectId);
}
