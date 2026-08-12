package com.fieldservice.photo.application;

import com.fieldservice.photo.domain.PhotoStorageException;
import com.fieldservice.photo.domain.PhotoStoragePort;
import com.fieldservice.photo.domain.UploadIntent;
import com.fieldservice.photo.domain.UploadIntentRepository;
import com.fieldservice.photo.infrastructure.PhotoStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Scheduled job that deletes storage objects whose upload intents were never consumed.
 *
 * <p>An unconsumed intent means the presigned PUT either never completed or registration
 * failed after upload. The grace window (default 24 h after expiry) ensures in-flight
 * uploads are not purged prematurely.
 *
 * <p>Runs in the worker profile only (scheduled tasks disabled in the API profile).
 */
@Component
public class PhotoOrphanCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PhotoOrphanCleanupJob.class);

    private final UploadIntentRepository intentRepository;
    private final PhotoStoragePort storage;
    private final PhotoStorageProperties props;

    public PhotoOrphanCleanupJob(UploadIntentRepository intentRepository,
                                   PhotoStoragePort storage,
                                   PhotoStorageProperties props) {
        this.intentRepository = intentRepository;
        this.storage = storage;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.storage.photo.orphan-cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupOrphans() {
        Instant cutoff = Instant.now()
                .minus(props.getPhoto().getOrphanGraceHours(), ChronoUnit.HOURS);

        List<UploadIntent> orphans = intentRepository.findOrphans(cutoff);
        if (orphans.isEmpty()) {
            return;
        }

        int deleted = 0;
        int failed = 0;

        for (UploadIntent intent : orphans) {
            try {
                storage.deleteObject(intent.getStorageKey());
                intentRepository.delete(intent);
                deleted++;
            } catch (PhotoStorageException e) {
                log.warn("photo.orphan_cleanup_failed: key={} workOrderId={}",
                        intent.getStorageKey(), intent.getWorkOrderId());
                failed++;
            }
        }

        log.info("photo.orphan_cleanup: deleted={} failed={} cutoff={}", deleted, failed, cutoff);
    }
}
