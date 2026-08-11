package com.fieldservice.asset.repository;

import com.fieldservice.asset.domain.Asset;
import com.fieldservice.platform.persistence.ScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRepository extends ScopedRepository<Asset, UUID> {

    List<Asset> findBySiteIdAndActiveTrue(UUID siteId);

    List<Asset> findBySiteIdIn(Collection<UUID> siteIds);
}
