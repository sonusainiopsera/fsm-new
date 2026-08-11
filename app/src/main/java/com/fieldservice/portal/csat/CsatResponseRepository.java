package com.fieldservice.portal.csat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CsatResponseRepository extends JpaRepository<CsatResponse, UUID> {

    boolean existsBySurveyId(UUID surveyId);
}
