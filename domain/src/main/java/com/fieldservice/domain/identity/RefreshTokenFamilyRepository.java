package com.fieldservice.domain.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamily, UUID> {

    List<RefreshTokenFamily> findByUserId(UUID userId);

    List<RefreshTokenFamily> findByUserIdAndRevokedAtIsNull(UUID userId);
}
