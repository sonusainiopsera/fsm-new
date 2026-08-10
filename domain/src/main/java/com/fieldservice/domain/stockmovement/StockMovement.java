package com.fieldservice.domain.stockmovement;

import com.fieldservice.domain.workorder.WorkOrder;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movements")
public class StockMovement implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    @Column(name = "part_number", nullable = false, length = 100)
    private String partNumber;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "technician_id", nullable = false)
    private String technicianId;

    @Column(name = "moved_at", nullable = false)
    private Instant movedAt;

    protected StockMovement() {}

    public StockMovement(WorkOrder workOrder, String partNumber, int quantity, String technicianId) {
        this.workOrder = workOrder;
        this.partNumber = partNumber;
        this.quantity = quantity;
        this.technicianId = technicianId;
        this.movedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public WorkOrder getWorkOrder() { return workOrder; }
    public String getPartNumber() { return partNumber; }
    public int getQuantity() { return quantity; }
    public String getTechnicianId() { return technicianId; }
    public Instant getMovedAt() { return movedAt; }
}
