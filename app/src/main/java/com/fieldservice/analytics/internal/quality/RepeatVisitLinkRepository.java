package com.fieldservice.analytics.internal.quality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface RepeatVisitLinkRepository extends JpaRepository<RepeatVisitLinkEntity, UUID> {

    boolean existsByEarlierWorkOrderIdAndLaterWorkOrderId(UUID earlierWorkOrderId, UUID laterWorkOrderId);
}
