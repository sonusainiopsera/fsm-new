package com.fieldservice.portal.csat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CsatResponseRepository extends JpaRepository<CsatResponse, UUID> {

    boolean existsBySurveyId(UUID surveyId);

    Optional<CsatResponse> findBySurveyId(UUID surveyId);
}
