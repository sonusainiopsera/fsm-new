package com.fieldservice.portal.csat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CsatSurveyRepository extends JpaRepository<CsatSurvey, UUID> {

    /** Paginated list scoped to a customer account (for portal list endpoint). */
    Page<CsatSurvey> findByAccountIdOrderByIssuedAtDesc(UUID accountId, Pageable pageable);

    /** Used by the scoped lookup in the response endpoint (account guard). */
    Optional<CsatSurvey> findByIdAndAccountId(UUID id, UUID accountId);
}
