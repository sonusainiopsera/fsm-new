package com.fieldservice.workorder.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "work_order_required_competency")
public class WorkOrderRequiredCompetency {

    @Id
    private UUID id;

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private UUID workOrderId;

    @Column(name = "certification_code", nullable = false, length = 50, updatable = false)
    private String certificationCode;

    protected WorkOrderRequiredCompetency() {}

    public WorkOrderRequiredCompetency(UUID workOrderId, String certificationCode) {
        this.id                = UuidV7.generate();
        this.workOrderId       = workOrderId;
        this.certificationCode = certificationCode;
    }

    public UUID   getId()                { return id; }
    public UUID   getWorkOrderId()       { return workOrderId; }
    public String getCertificationCode() { return certificationCode; }
}
