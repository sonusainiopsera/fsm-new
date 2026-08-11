package com.fieldservice.platform.entity;

import com.fieldservice.platform.util.GeneratedUuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.UUID;

/**
 * Common superclass for all domain entities.
 *
 * <p>Provides:
 * <ul>
 *   <li>UUIDv7 primary key (time-ordered for B-tree locality, non-guessable)</li>
 *   <li>Optimistic-locking {@code version} column wired to JPA {@code @Version}</li>
 *   <li>Audit timestamps {@code created_at} and {@code updated_at}</li>
 * </ul>
 *
 * <p>Entities that require row-scope enforcement extend this class AND implement
 * {@link com.fieldservice.platform.persistence.ScopedEntity}.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedUuidV7
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BaseEntity() {
    }

    public UUID getId() {
        return id;
    }

    /** For test fixtures and migration data that supply a pre-determined ID. */
    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : System.identityHashCode(this);
    }
}
