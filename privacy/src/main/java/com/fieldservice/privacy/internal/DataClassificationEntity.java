package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.ClassificationTier;
import com.fieldservice.privacy.api.ClassificationView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Audited
@Entity
@Table(name = "data_classification")
class DataClassificationEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "module", nullable = false, length = 100)
    private String module;

    @Column(name = "entity_name", nullable = false, length = 255)
    private String entityName;

    @Column(name = "field_name", length = 255)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 20)
    private ClassificationTier tier;

    @Column(name = "lawful_basis_note", columnDefinition = "text")
    private String lawfulBasisNote;

    @Column(name = "handling_notes", columnDefinition = "text")
    private String handlingNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    protected DataClassificationEntity() {}

    ClassificationView toView() {
        return new ClassificationView(id, module, entityName, fieldName, tier,
                lawfulBasisNote, handlingNotes, createdAt, createdBy, updatedAt, updatedBy, version);
    }

    UUID             getId()            { return id; }
    String           getModule()        { return module; }
    String           getEntityName()    { return entityName; }
    String           getFieldName()     { return fieldName; }
    ClassificationTier getTier()        { return tier; }
    String           getLawfulBasisNote(){ return lawfulBasisNote; }
    String           getHandlingNotes() { return handlingNotes; }
    int              getVersion()       { return version; }

    void setTier(ClassificationTier tier)           { this.tier = tier; }
    void setLawfulBasisNote(String lawfulBasisNote) { this.lawfulBasisNote = lawfulBasisNote; }
    void setHandlingNotes(String handlingNotes)     { this.handlingNotes = handlingNotes; }
    void setUpdatedAt(Instant updatedAt)            { this.updatedAt = updatedAt; }
    void setUpdatedBy(String updatedBy)             { this.updatedBy = updatedBy; }
}
