package com.fieldservice.dispatch.scoring.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DispatchScoringWeightRepository extends JpaRepository<DispatchScoringWeight, UUID> {
    List<DispatchScoringWeight> findAllByOrderByFactorCode();
}
