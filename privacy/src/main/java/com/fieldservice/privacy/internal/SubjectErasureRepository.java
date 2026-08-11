package com.fieldservice.privacy.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Append-only repository for {@link SubjectErasure} tombstones.
 *
 * <p>No {@code deleteBy*} or {@code update*} methods — the tombstone is immutable
 * once written. The unique partial index on (subject_type, subject_id) WHERE
 * outcome='COMPLETED' is enforced at the database level.
 */
interface SubjectErasureRepository extends JpaRepository<SubjectErasure, UUID> {

    Optional<SubjectErasure> findBySubjectTypeAndSubjectIdAndOutcome(
            String subjectType, UUID subjectId, String outcome);

    boolean existsBySubjectTypeAndSubjectIdAndOutcome(
            String subjectType, UUID subjectId, String outcome);
}
