package com.fieldservice.app.privacy;

import com.fieldservice.photo.repository.WorkOrderPhotoRepository;
import com.fieldservice.privacy.api.RetentionTarget;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * RetentionTarget for the SITE_PHOTOGRAPHS data category.
 *
 * <p>Delegates to WorkOrderPhotoRepository to find and dispose photos whose
 * retain_until date has passed the cutoff instant. Disposal means physical
 * deletion from the database row; object-storage cleanup is handled by a
 * separate scheduled job that uses the storage_key to remove the binary.
 */
@Component
class SitePhotographsTarget implements RetentionTarget {

    private final WorkOrderPhotoRepository photoRepository;

    SitePhotographsTarget(WorkOrderPhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
    }

    @Override
    public String getDataCategory() {
        return "SITE_PHOTOGRAPHS";
    }

    @Override
    public long countEligible(Instant cutoff) {
        LocalDate cutoffDate = LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
        return photoRepository.countByRetainUntilBefore(cutoffDate);
    }

    @Override
    public Instant oldestEligibleAt(Instant cutoff) {
        LocalDate cutoffDate = LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
        return photoRepository.findOldestRetainUntilBefore(cutoffDate)
                .map(d -> d.atStartOfDay().toInstant(ZoneOffset.UTC))
                .orElse(null);
    }

    @Override
    public List<UUID> pageEligibleIds(Instant cutoff, int pageSize) {
        LocalDate cutoffDate = LocalDate.ofInstant(cutoff, ZoneOffset.UTC);
        return photoRepository.findIdsWithRetainUntilBefore(cutoffDate, pageSize);
    }

    @Override
    public void disposeBatch(List<UUID> ids) {
        photoRepository.deleteAllById(ids);
    }
}
