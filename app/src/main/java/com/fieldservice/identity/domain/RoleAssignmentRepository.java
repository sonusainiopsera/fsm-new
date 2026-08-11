package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

    List<RoleAssignment> findByUserId(UUID userId);

    boolean existsByUserIdAndRoleName(UUID userId, AppRole roleName);
}
