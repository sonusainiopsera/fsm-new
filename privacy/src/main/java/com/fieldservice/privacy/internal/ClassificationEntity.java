package com.fieldservice.privacy.internal;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

/**
 * JPA entity for {@code data_classification} table rows.
 *
 * <p>Package-private — callers outside this module must use {@link ClassificationView}
 * obtained through the {@link com.fieldservice.privacy.api.ClassificationRegistry} interface.
 *
 * <p>Single-writer invariant: the distributed lock in {@link ClassificationRegistryImpl}
 * ensures concurrent PUTs are serialised; optimistic locking ({@code @Version} from
 * {@link BaseEntity}) is the last line of defence and returns HTTP 409 on conflict.
 *
 * <p>Audited with Hibernate Envers — every tier change produces a revision in
 * {@code data_classification_aud}.
 */
@Audited
@Entity
@Table(name = "data_classification")
class ClassificationEntity extends BaseEntity {

    @Column(name = "module", nullable = false, length = 100)
    private String module;

    @Column(name = "entity_name", nullable = false, length = 200)
    private String entityName;

    @Nullable
    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 20)
    private ClassificationTier tier;

    @Nullable
    @Column(name = "lawful_basis_note", columnDefinition = "text")
    private String lawfulBasisNote;

    @Nullable
    @Column(name = "handling_notes", columnDefinition = "text")
    private String handlingNotes;

    @Nullable
    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Nullable
    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected ClassificationEntity() {
    }

    ClassificationEntity(String module, String entityName, @Nullable String fieldName,
                         ClassificationTier tier, @Nullable String lawfulBasisNote,
                         @Nullable String handlingNotes) {
        this.module = module;
        this.entityName = entityName;
        this.fieldName = fieldName;
        this.tier = tier;
        this.lawfulBasisNote = lawfulBasisNote;
        this.handlingNotes = handlingNotes;
    }

    ClassificationView toView() {
        return new ClassificationView(
                getId(), module, entityName, fieldName, tier,
                lawfulBasisNote, handlingNotes,
                getVersion() == null ? 0 : getVersion(),
                getUpdatedAt(), updatedBy);
    }

    void applyUpdate(ClassificationTier newTier, @Nullable String newLawfulBasisNote,
                     @Nullable String newHandlingNotes, @Nullable String actor) {
        this.tier = newTier;
        this.lawfulBasisNote = newLawfulBasisNote;
        this.handlingNotes = newHandlingNotes;
        this.updatedBy = actor;
    }

    String getEntityName() {
        return entityName;
    }

    @Nullable
    String getFieldName() {
        return fieldName;
    }

    ClassificationTier getTier() {
        return tier;
    }

    String getModule() {
        return module;
    }
}
