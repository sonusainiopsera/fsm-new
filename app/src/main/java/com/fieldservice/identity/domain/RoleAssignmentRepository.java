package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

    List<RoleAssignment> findByUserId(UUID userId);

    boolean existsByUserIdAndRoleName(UUID userId, IdentityRole roleName);
}
