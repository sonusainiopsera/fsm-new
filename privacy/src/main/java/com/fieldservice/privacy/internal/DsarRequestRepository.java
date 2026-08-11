package com.fieldservice.privacy.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Package-private JPA repository for {@link DsarRequest} entities. */
interface DsarRequestRepository extends JpaRepository<DsarRequest, UUID> {

    Page<DsarRequest> findAllByOrderByDueAtAscIdAsc(Pageable pageable);

    Page<DsarRequest> findByStateOrderByDueAtAscIdAsc(DsarState state, Pageable pageable);

    List<DsarRequest> findByState(DsarState state);

    /** Detects duplicate in-flight requests for the same subject. */
    List<DsarRequest> findBySubjectTypeAndSubjectIdAndStateNotIn(
            String subjectType, UUID subjectId, List<DsarState> excludedStates);

}
