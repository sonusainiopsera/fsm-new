package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RefreshTokenFamilyRepository extends JpaRepository<RefreshTokenFamily, UUID> {

    List<RefreshTokenFamily> findByUserIdAndRevokedAtIsNull(UUID userId);
}
