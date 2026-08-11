package com.fieldservice.domain.technician;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.platform.entity.BaseEntity;
import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A field engineer profile linked to an {@link AppUser}.
 *
 * <p>The {@code technician.id} is the row-scope boundary for TECHNICIAN principals:
 * it corresponds to the {@code technicianId} claim in the JWT.
 *
 * <p>Scoped entity:
 * <ul>
 *   <li>TECHNICIAN — sees only their own record.</li>
 *   <li>DISPATCHER / ADMIN / MANAGER — permit-all.</li>
 *   <li>CUSTOMER — deny-all (technician profiles are not customer-facing).</li>
 * </ul>
 */
@Entity
@Table(name = "technician")
public class Technician extends BaseEntity implements ScopedEntity {

    @Column(name = "user_id", nullable = false, insertable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "employee_no", length = 50)
    private String employeeNo;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Technician() {
    }

    public UUID getUserId() { return userId; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) {
        this.user = user;
        this.userId = user != null ? user.getId() : null;
    }

    public String getEmployeeNo() { return employeeNo; }
    public void setEmployeeNo(String employeeNo) { this.employeeNo = employeeNo; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
