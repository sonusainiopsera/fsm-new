package com.fieldservice.domain.site;

import com.fieldservice.platform.persistence.ScopedEntity;
import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "site")
public class Site implements ScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(length = 500)
    private String address;

    protected Site() {}

    public Site(String name, UUID customerId, String address) {
        this.id = UuidV7.generate();
        this.name = name;
        this.customerId = customerId;
        this.address = address;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getCustomerId() { return customerId; }
    public String getAddress() { return address; }

    public void setName(String name) { this.name = name; }
    public void setAddress(String address) { this.address = address; }
}
