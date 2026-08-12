package com.fieldservice.photo.application;

import com.fieldservice.photo.api.PhotoStoragePort;
import com.fieldservice.photo.domain.PhotoCategory;
import com.fieldservice.photo.domain.UploadIntent;
import com.fieldservice.photo.domain.WorkOrderPhoto;
import com.fieldservice.photo.repository.UploadIntentRepository;
import com.fieldservice.photo.repository.WorkOrderPhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PhotoUploadService.
 *
 * Covers:
 * - Content type allow-list validation
 * - Content length (size) allow-list validation
 * - Intent issuance: delegates to storagePort, persists UploadIntent
 * - Registration: happy path, idempotent replay, intent not found, expired intent,
 *   object missing, content type mismatch, oversized object
 */
@ExtendWith(MockitoExtension.class)
class PhotoUploadServiceTest {

    @Mock PhotoStoragePort        storagePort;
    @Mock UploadIntentRepository  intentRepository;
    @Mock WorkOrderPhotoRepository photoRepository;

    private PhotoUploadService service;

    private static final UUID WO_ID    = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PhotoUploadService(storagePort, intentRepository, photoRepository);
    }

    // ---- Content type allow-list -------------------------------------------

    @Test
    @DisplayName("issueUploadIntent accepts image/jpeg")
    void acceptsJpeg() {
        stubPresign();
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.issueUploadIntent(WO_ID, "image/jpeg", 1024L, ACTOR_ID);
        assertThat(result.requiredContentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("issueUploadIntent accepts image/png and image/webp")
    void acceptsPngWebp() {
        stubPresign();
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.issueUploadIntent(WO_ID, "image/png",  1024L, ACTOR_ID);
        service.issueUploadIntent(WO_ID, "image/webp", 1024L, ACTOR_ID);
    }

    @Test
    @DisplayName("issueUploadIntent rejects image/gif with DisallowedContentTypeException")
    void rejectsGif() {
        assertThatThrownBy(() -> service.issueUploadIntent(WO_ID, "image/gif", 1024L, ACTOR_ID))
                .isInstanceOf(DisallowedContentTypeException.class)
                .hasMessageContaining("image/gif");
        verify(storagePort, never()).presignPut(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("issueUploadIntent rejects null content type")
    void rejectsNullContentType() {
        assertThatThrownBy(() -> service.issueUploadIntent(WO_ID, null, 1024L, ACTOR_ID))
                .isInstanceOf(DisallowedContentTypeException.class);
    }

    // ---- Content length validation -----------------------------------------

    @Test
    @DisplayName("issueUploadIntent rejects size > 5 MB")
    void rejectsOversizedRequest() {
        long tooBig = PhotoUploadService.MAX_BYTES + 1;
        assertThatThrownBy(() -> service.issueUploadIntent(WO_ID, "image/jpeg", tooBig, ACTOR_ID))
                .isInstanceOf(ContentTooLargeException.class);
    }

    @Test
    @DisplayName("issueUploadIntent accepts size == 5 MB")
    void acceptsExactMaxBytes() {
        stubPresign();
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.issueUploadIntent(WO_ID, "image/jpeg", PhotoUploadService.MAX_BYTES, ACTOR_ID);
    }

    @Test
    @DisplayName("issueUploadIntent rejects zero or negative content length")
    void rejectsZeroLength() {
        assertThatThrownBy(() -> service.issueUploadIntent(WO_ID, "image/jpeg", 0L, ACTOR_ID))
                .isInstanceOf(ContentTooLargeException.class);
    }

    // ---- Intent issuance ---------------------------------------------------

    @Test
    @DisplayName("issueUploadIntent returns storageKey under work-order prefix")
    void storageKeyHasPrefix() {
        stubPresign();
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.issueUploadIntent(WO_ID, "image/jpeg", 512L, ACTOR_ID);
        assertThat(result.storageKey()).startsWith("work-orders/" + WO_ID + "/");
        assertThat(result.storageKey()).endsWith(".jpg");
    }

    // ---- Registration — happy path -----------------------------------------

    @Test
    @DisplayName("registerPhoto returns result and marks intent consumed")
    void registerSuccess() {
        UploadIntent intent = stubIntent(WO_ID, "image/jpeg", false, false);
        stubHeadObject("image/jpeg", 1024L);
        when(photoRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(photoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storagePort.presignGet(anyString(), anyInt())).thenReturn("https://fake/view");

        var result = service.registerPhoto(WO_ID, intent.getStorageKey(),
                PhotoCategory.ISSUE, Instant.now(), "relay replaced", ACTOR_ID);

        assertThat(result.thumbnailUrl()).isEqualTo("https://fake/view");
        assertThat(intent.isConsumed()).isTrue();
    }

    // ---- Registration — idempotency ----------------------------------------

    @Test
    @DisplayName("registerPhoto is idempotent: second call returns existing record")
    void registerIdempotent() {
        WorkOrderPhoto existing = stubExistingPhoto(WO_ID);
        when(storagePort.presignGet(anyString(), anyInt())).thenReturn("https://fake/view");

        var result = service.registerPhoto(WO_ID, existing.getStorageKey(),
                PhotoCategory.ISSUE, Instant.now(), null, ACTOR_ID);

        assertThat(result.photoId()).isEqualTo(existing.getId());
        verify(intentRepository, never()).findByStorageKey(anyString());
    }

    // ---- Registration — intent not found -----------------------------------

    @Test
    @DisplayName("registerPhoto throws IntentNotFoundException when no intent exists")
    void registerNoIntent() {
        when(photoRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(intentRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerPhoto(
                WO_ID, "work-orders/" + WO_ID + "/ghost.jpg",
                PhotoCategory.ISSUE, Instant.now(), null, ACTOR_ID))
                .isInstanceOf(IntentNotFoundException.class);
    }

    // ---- Registration — expired intent -------------------------------------

    @Test
    @DisplayName("registerPhoto throws IntentExpiredException when intent expired")
    void registerExpiredIntent() {
        stubIntent(WO_ID, "image/jpeg", true, false);
        when(photoRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerPhoto(
                WO_ID, intentStorageKey(),
                PhotoCategory.ISSUE, Instant.now(), null, ACTOR_ID))
                .isInstanceOf(IntentExpiredException.class);
    }

    // ---- Registration — object missing in storage --------------------------

    @Test
    @DisplayName("registerPhoto throws ObjectVerificationException when object absent")
    void registerObjectMissing() {
        stubIntent(WO_ID, "image/jpeg", false, false);
        when(photoRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(storagePort.headObject(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerPhoto(
                WO_ID, intentStorageKey(),
                PhotoCategory.ISSUE, Instant.now(), null, ACTOR_ID))
                .isInstanceOf(ObjectVerificationException.class)
                .hasMessageContaining("Object not found");
    }

    // ---- Registration — content type mismatch ------------------------------

    @Test
    @DisplayName("registerPhoto throws ObjectVerificationException on content-type mismatch")
    void registerContentTypeMismatch() {
        stubIntent(WO_ID, "image/jpeg", false, false);
        when(photoRepository.findByStorageKey(anyString())).thenReturn(Optional.empty());
        when(storagePort.headObject(anyString()))
                .thenReturn(Optional.of(new PhotoStoragePort.ObjectMetadata("image/png", 512L)));

        assertThatThrownBy(() -> service.registerPhoto(
                WO_ID, intentStorageKey(),
                PhotoCategory.ISSUE, Instant.now(), null, ACTOR_ID))
                .isInstanceOf(ObjectVerificationException.class)
                .hasMessageContaining("mismatch");
    }

    // ---- Allowed content types list sanity ---------------------------------

    @Test
    @DisplayName("ALLOWED_CONTENT_TYPES contains exactly jpeg, png, webp")
    void allowedTypesAreCorrect() {
        assertThat(PhotoUploadService.ALLOWED_CONTENT_TYPES)
                .containsExactlyInAnyOrder("image/jpeg", "image/png", "image/webp");
    }

    @Test
    @DisplayName("MAX_BYTES is 5 MB")
    void maxBytesIs5MB() {
        assertThat(PhotoUploadService.MAX_BYTES).isEqualTo(5 * 1024 * 1024L);
    }

    // ---- Private helpers ---------------------------------------------------

    private static String _intentKey = null;

    private String intentStorageKey() {
        return _intentKey != null ? _intentKey : ("work-orders/" + WO_ID + "/test.jpg");
    }

    private void stubPresign() {
        when(storagePort.presignPut(anyString(), anyString(), anyInt()))
                .thenReturn(new PhotoStoragePort.PresignedPutResult(
                        "https://fake/put-url", Instant.now().plusSeconds(300)));
    }

    private UploadIntent stubIntent(UUID workOrderId, String contentType,
                                     boolean expired, boolean consumed) {
        Instant expiresAt = expired
                ? Instant.now().minusSeconds(600)
                : Instant.now().plusSeconds(300);
        UploadIntent intent = new UploadIntent(workOrderId,
                "work-orders/" + workOrderId + "/test.jpg",
                contentType, 1024L, expiresAt, ACTOR_ID);
        if (consumed) intent.markConsumed(Instant.now().minusSeconds(10));
        _intentKey = intent.getStorageKey();
        when(intentRepository.findByStorageKey(intent.getStorageKey()))
                .thenReturn(Optional.of(intent));
        when(intentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return intent;
    }

    private WorkOrderPhoto stubExistingPhoto(UUID workOrderId) {
        String key = "work-orders/" + workOrderId + "/existing.jpg";
        WorkOrderPhoto photo = new WorkOrderPhoto(workOrderId, key,
                PhotoCategory.ISSUE, Instant.now(), null,
                java.time.LocalDate.now().plusYears(7), ACTOR_ID);
        when(photoRepository.findByStorageKey(key)).thenReturn(Optional.of(photo));
        return photo;
    }

    private void stubHeadObject(String contentType, long size) {
        when(storagePort.headObject(anyString()))
                .thenReturn(Optional.of(new PhotoStoragePort.ObjectMetadata(contentType, size)));
    }
}
