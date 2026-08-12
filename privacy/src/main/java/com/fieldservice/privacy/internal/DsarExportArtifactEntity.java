package com.fieldservice.privacy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dsar_export_artifact")
class DsarExportArtifactEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "dsar_request_id", nullable = false, updatable = false)
    private UUID dsarRequestId;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "manifest", columnDefinition = "jsonb")
    private String manifest;

    @Column(name = "export_json", columnDefinition = "text")
    private String exportJson;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "disposed_at")
    private Instant disposedAt;

    protected DsarExportArtifactEntity() {}

    static DsarExportArtifactEntity create(UUID id, UUID dsarRequestId, String storageKey,
                                           String manifestJson, String exportJson,
                                           long byteSize, Instant generatedAt) {
        DsarExportArtifactEntity e = new DsarExportArtifactEntity();
        e.id            = id;
        e.dsarRequestId = dsarRequestId;
        e.storageKey    = storageKey;
        e.manifest      = manifestJson;
        e.exportJson    = exportJson;
        e.byteSize      = byteSize;
        e.generatedAt   = generatedAt;
        return e;
    }

    void dispose(Instant now) {
        this.exportJson  = null;
        this.disposedAt  = now;
    }

    UUID    getId()           { return id; }
    UUID    getDsarRequestId(){ return dsarRequestId; }
    String  getStorageKey()   { return storageKey; }
    String  getManifest()     { return manifest; }
    String  getExportJson()   { return exportJson; }
    Long    getByteSize()     { return byteSize; }
    Instant getGeneratedAt()  { return generatedAt; }
    Instant getDisposedAt()   { return disposedAt; }
}
