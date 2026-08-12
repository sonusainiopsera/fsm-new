package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DsarState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface DsarRequestRepository extends JpaRepository<DsarRequestEntity, UUID> {

    Page<DsarRequestEntity> findAll(Pageable pageable);

    Page<DsarRequestEntity> findByState(DsarState state, Pageable pageable);

    @Query("SELECT r FROM DsarRequestEntity r WHERE r.state = 'VERIFIED' ORDER BY r.dueAt ASC")
    List<DsarRequestEntity> findAllVerifiedOrderByDueAt();

    @Query("SELECT COUNT(r) FROM DsarRequestEntity r " +
           "WHERE r.state IN ('FULFILLED', 'REJECTED', 'WITHDRAWN')")
    long countClosed();

    @Query("SELECT COUNT(r) FROM DsarRequestEntity r " +
           "WHERE r.state = 'FULFILLED' AND r.dueAt >= r.submittedAt")
    long countFulfilledOnTime();

    @Query("SELECT COUNT(r) FROM DsarRequestEntity r " +
           "WHERE r.state NOT IN ('FULFILLED', 'REJECTED', 'WITHDRAWN')")
    long countOpen();
}
