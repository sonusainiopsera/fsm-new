package com.fieldservice.catalog.event;

import java.util.UUID;

public record CustomerChanged(
        UUID   customerId,
        String accountCode,
        String legalName,
        boolean active,
        String changeType
) {}
