package com.fieldservice.site.repository;

import com.fieldservice.platform.persistence.ScopedRepository;
import com.fieldservice.site.domain.Site;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SiteRepository extends ScopedRepository<Site, UUID> {

    List<Site> findByCustomerIdAndActiveTrue(UUID customerId);
}
