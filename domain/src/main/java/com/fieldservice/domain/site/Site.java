package com.fieldservice.domain.site;

import com.fieldservice.platform.persistence.ScopedEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "sites")
public class Site implements ScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "customer_account_id", nullable = false)
    private UUID customerAccountId;

    @Column(length = 500)
    private String address;

    protected Site() {}

    public Site(String name, UUID customerAccountId, String address) {
        this.name = name;
        this.customerAccountId = customerAccountId;
        this.address = address;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getCustomerAccountId() { return customerAccountId; }
    public String getAddress() { return address; }

    public void setName(String name) { this.name = name; }
    public void setAddress(String address) { this.address = address; }
}
