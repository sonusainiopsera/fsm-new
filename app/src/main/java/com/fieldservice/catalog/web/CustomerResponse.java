package com.fieldservice.catalog.web;

import com.fieldservice.customer.domain.CustomerAccount;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
        UUID    id,
        String  accountCode,
        String  legalName,
        String  primaryContactName,
        boolean active,
        Instant createdAt
) {
    public static CustomerResponse from(CustomerAccount c) {
        return new CustomerResponse(
                c.getId(),
                c.getAccountCode(),
                c.getLegalName() != null ? c.getLegalName() : c.getName(),
                c.getPrimaryContactName(),
                c.isActive(),
                c.getCreatedAt()
        );
    }
}
