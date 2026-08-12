package com.fieldservice.app.privacy;

import com.fieldservice.privacy.api.RetentionTarget;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RetentionTarget for the SITE_PHOTOGRAPHS data category.
 *
 * <p>Placeholder stub — site photograph storage mechanism (blob store vs. DB column)
 * is not yet finalised. Returns zero counts until the storage decision is made.
 */
@Component
class SitePhotographsTarget implements RetentionTarget {

    @Override
    public String getDataCategory() {
        return "SITE_PHOTOGRAPHS";
    }

    @Override
    public long countEligible(Instant cutoff) {
        return 0L;
    }

    @Override
    public Instant oldestEligibleAt(Instant cutoff) {
        return null;
    }

    @Override
    public List<UUID> pageEligibleIds(Instant cutoff, int pageSize) {
        return List.of();
    }

    @Override
    public void disposeBatch(List<UUID> ids) {
        throw new UnsupportedOperationException(
                "SitePhotographsTarget.disposeBatch not yet implemented — storage mechanism not finalised");
    }
}
