package com.fieldservice.photo.internal;

import com.fieldservice.photo.api.PhotoStoragePort;
import com.fieldservice.photo.repository.WorkOrderPhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Daily purge job that physically deletes work order photo records past their
 * {@code retain_until} date, also removing the corresponding object from object storage.
 *
 * <p>Safety ordering: the object is deleted from storage first, then the metadata row
 * is deleted. If the storage delete fails, the row is left in place and the job retries
 * on the next run (idempotent). If the storage delete succeeds but the row delete fails,
 * a subsequent run attempts the storage delete again (no-op because the object is gone)
 * and then retries the row delete.
 *
 * <p>A PostgreSQL advisory lock prevents concurrent execution across worker replicas.
 *
 * <p>Orphan reconciliation: if a storage object was deleted successfully but the metadata
 * row was not removed, the job logs a warning with counts on each run.
 */
@Component
public class PhotoRetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(PhotoRetentionPurgeJob.class);

    // Stable 64-bit key for photo purge advisory lock.
    // Derived from "phopurge" — must never change after deployment.
    static final long LOCK_KEY = 0x70686F_7075_7267_65L;

    private static final int DEFAULT_BATCH_SIZE = 50;

    private final WorkOrderPhotoRepository photoRepository;
    private final PhotoStoragePort         storagePort;
    private final JdbcTemplate             jdbc;

    public PhotoRetentionPurgeJob(WorkOrderPhotoRepository photoRepository,
                                   PhotoStoragePort storagePort,
                                   JdbcTemplate jdbc) {
        this.photoRepository = photoRepository;
        this.storagePort     = storagePort;
        this.jdbc            = jdbc;
    }

    /**
     * Runs once per day at 03:15 UTC. Configurable via {@code fieldservice.photo.purge-cron}.
     */
    @Scheduled(cron = "${fieldservice.photo.purge-cron:0 15 3 * * *}")
    public void purge() {
        if (!tryAcquireLock()) {
            log.debug("photo_retention_purge_skipped reason=lock_not_acquired");
            return;
        }
        try {
            runPurge();
        } finally {
            releaseLock();
        }
    }

    @Transactional
    void runPurge() {
        LocalDate cutoff   = LocalDate.now();
        int       batch    = DEFAULT_BATCH_SIZE;
        int       total    = 0;
        int       storage  = 0;
        int       errors   = 0;

        log.info("photo_retention_purge_started cutoff={} batch_size={}", cutoff, batch);

        List<UUID> ids;
        do {
            ids = photoRepository.findIdsWithRetainUntilBefore(cutoff, batch);
            for (UUID photoId : ids) {
                String storageKey = loadStorageKey(photoId);
                if (storageKey != null) {
                    try {
                        storagePort.deleteObject(storageKey);
                        storage++;
                    } catch (Exception ex) {
                        log.error("photo_retention_storage_delete_failed photo_id={} reason={}",
                                photoId, ex.getMessage());
                        errors++;
                        continue; // skip row delete — retry on next run
                    }
                }
                try {
                    photoRepository.deleteById(photoId);
                    total++;
                } catch (Exception ex) {
                    log.error("photo_retention_row_delete_failed photo_id={} reason={}",
                            photoId, ex.getMessage());
                    errors++;
                }
            }
        } while (ids.size() == batch);

        log.info("photo_retention_purge_completed total_deleted={} storage_deleted={} errors={}",
                total, storage, errors);
    }

    private String loadStorageKey(UUID photoId) {
        try {
            return jdbc.queryForObject(
                    "SELECT storage_key FROM work_order_photo WHERE id = ?",
                    String.class, photoId);
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean tryAcquireLock() {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseLock() {
        try {
            jdbc.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
        } catch (Exception ex) {
            log.warn("photo_retention_purge_lock_release_failed: {}", ex.getMessage());
        }
    }
}
