package com.fieldservice.privacy.internal;

import com.fieldservice.privacy.api.DisposalMethod;
import com.fieldservice.privacy.api.RetentionPeriodUnit;
import com.fieldservice.privacy.api.RetentionPolicyView;
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
@Table(name = "retention_policy")
class RetentionPolicyEntity {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "data_category", nullable = false, unique = true, length = 100)
    private String dataCategory;

    @Column(name = "entity_name", length = 255)
    private String entityName;

    @Column(name = "period_value", nullable = false)
    private int periodValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_unit", nullable = false, length = 10)
    private RetentionPeriodUnit periodUnit;

    @Column(name = "anchor_field", nullable = false, length = 255)
    private String anchorField;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposal_method", nullable = false, length = 20)
    private DisposalMethod disposalMethod;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "ratified", nullable = false)
    private boolean ratified;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

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

    protected RetentionPolicyEntity() {}

    RetentionPolicyView toView() {
        return new RetentionPolicyView(id, dataCategory, entityName, periodValue, periodUnit,
                anchorField, disposalMethod, legalHold, ratified, enabled, notes,
                createdAt, createdBy, updatedAt, updatedBy, version);
    }

    UUID               getId()             { return id; }
    String             getDataCategory()   { return dataCategory; }
    String             getEntityName()     { return entityName; }
    int                getPeriodValue()    { return periodValue; }
    RetentionPeriodUnit getPeriodUnit()    { return periodUnit; }
    String             getAnchorField()    { return anchorField; }
    DisposalMethod     getDisposalMethod() { return disposalMethod; }
    boolean            isLegalHold()       { return legalHold; }
    boolean            isRatified()        { return ratified; }
    boolean            isEnabled()         { return enabled; }
    int                getVersion()        { return version; }

    void setPeriodValue(int periodValue)            { this.periodValue = periodValue; }
    void setPeriodUnit(RetentionPeriodUnit unit)     { this.periodUnit = unit; }
    void setDisposalMethod(DisposalMethod method)    { this.disposalMethod = method; }
    void setLegalHold(boolean legalHold)             { this.legalHold = legalHold; }
    void setRatified(boolean ratified)               { this.ratified = ratified; }
    void setEnabled(boolean enabled)                 { this.enabled = enabled; }
    void setNotes(String notes)                      { this.notes = notes; }
    void setUpdatedAt(Instant updatedAt)             { this.updatedAt = updatedAt; }
    void setUpdatedBy(String updatedBy)              { this.updatedBy = updatedBy; }
}
