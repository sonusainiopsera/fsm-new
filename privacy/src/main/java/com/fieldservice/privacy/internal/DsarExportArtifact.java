package com.fieldservice.privacy.internal;

import com.fieldservice.platform.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for {@code dsar_export_artifact} rows.
 *
 * <p>Package-private — append-only; no public setters except {@code dispose()}.
 *
 * <p>The {@code storageKey} identifies the object in the configured export storage backend.
 * The {@code manifest} column holds a JSON array of section metadata entries.
 * The {@code exportData} column holds the full export JSON for local storage mode.
 */
@Audited
@Entity
@Table(name = "dsar_export_artifact")
class DsarExportArtifact extends BaseEntity {

    @Column(name = "dsar_request_id", nullable = false, updatable = false)
    private UUID dsarRequestId;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "manifest", columnDefinition = "jsonb", nullable = false)
    private String manifest;

    @Nullable
    @Column(name = "export_data", columnDefinition = "text")
    private String exportData;

    @Nullable
    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Nullable
    @Column(name = "disposed_at")
    private Instant disposedAt;

    protected DsarExportArtifact() {
    }

    DsarExportArtifact(UUID dsarRequestId, String storageKey, String manifest,
                       @Nullable String exportData, @Nullable Long byteSize,
                       Instant generatedAt) {
        this.dsarRequestId = dsarRequestId;
        this.storageKey = storageKey;
        this.manifest = manifest;
        this.exportData = exportData;
        this.byteSize = byteSize;
        this.generatedAt = generatedAt;
    }

    void dispose(Instant disposedAt) {
        this.disposedAt = disposedAt;
        this.exportData = null;
    }

    // ── Accessors ────────────────────────────────────────────────────────────────

    UUID getDsarRequestId() { return dsarRequestId; }
    String getStorageKey() { return storageKey; }
    String getManifest() { return manifest; }
    @Nullable String getExportData() { return exportData; }
    @Nullable Long getByteSize() { return byteSize; }
    Instant getGeneratedAt() { return generatedAt; }
    @Nullable Instant getDisposedAt() { return disposedAt; }
}
