package com.fieldservice.privacy.internal;

import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.privacy.api.RetentionPolicyView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import org.springframework.lang.Nullable;

/**
 * JPA entity for {@code retention_policy} rows.
 *
 * <p>Package-private — all external access must go through
 * {@link com.fieldservice.privacy.api.RetentionPolicyAdminPort}.
 *
 * <p>Audited with Hibernate Envers; every policy change produces a revision in
 * {@code retention_policy_aud}.
 */
@Audited
@Entity
@Table(name = "retention_policy")
class RetentionPolicy extends BaseEntity {

    @Column(name = "data_category", nullable = false, unique = true, length = 100)
    private String dataCategory;

    @Column(name = "entity_name", nullable = false, length = 200)
    private String entityName;

    @Column(name = "period_value", nullable = false)
    private int periodValue;

    @Column(name = "period_unit", nullable = false, length = 10)
    private String periodUnit;

    @Column(name = "anchor_field", nullable = false, length = 100)
    private String anchorField;

    @Column(name = "disposal_method", nullable = false, length = 20)
    private String disposalMethod;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "ratified", nullable = false)
    private boolean ratified;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Nullable
    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    protected RetentionPolicy() {
    }

    RetentionPolicyView toView() {
        return new RetentionPolicyView(
                getId(), dataCategory, entityName,
                periodValue, periodUnit, anchorField,
                disposalMethod, legalHold, ratified, enabled,
                notes,
                getVersion() == null ? 0 : getVersion(),
                getCreatedAt(), getUpdatedAt());
    }

    void applyUpdate(int periodValue, String periodUnit, String disposalMethod,
                     boolean legalHold, boolean ratified, boolean enabled,
                     @Nullable String notes) {
        this.periodValue = periodValue;
        this.periodUnit = periodUnit;
        this.disposalMethod = disposalMethod;
        this.legalHold = legalHold;
        this.ratified = ratified;
        this.enabled = enabled;
        this.notes = notes;
    }

    String getDataCategory() { return dataCategory; }
    String getEntityName() { return entityName; }
    int getPeriodValue() { return periodValue; }
    String getPeriodUnit() { return periodUnit; }
    String getAnchorField() { return anchorField; }
    String getDisposalMethod() { return disposalMethod; }
    boolean isLegalHold() { return legalHold; }
    boolean isRatified() { return ratified; }
    boolean isEnabled() { return enabled; }
    @Nullable String getNotes() { return notes; }
}
