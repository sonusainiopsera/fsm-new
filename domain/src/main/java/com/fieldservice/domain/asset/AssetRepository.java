package com.fieldservice.domain.asset;

import com.fieldservice.platform.persistence.ScopedRepository;
import java.util.UUID;

public interface AssetRepository extends ScopedRepository<Asset, UUID> {
}
