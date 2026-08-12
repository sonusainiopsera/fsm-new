package com.fieldservice.dispatch.scoring.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DispatchScoringConfigRepository extends JpaRepository<DispatchScoringConfig, UUID> {
    Optional<DispatchScoringConfig> findByConfigKey(String configKey);
}
