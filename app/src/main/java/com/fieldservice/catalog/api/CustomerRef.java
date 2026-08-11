package com.fieldservice.catalog.api;

import java.util.UUID;

/**
 * Read model for a customer record.
 *
 * <p>This record is the only customer representation visible outside the catalog module.
 * Entities and repositories are never exported beyond the catalog boundary.
 *
 * @param id          customer UUID primary key
 * @param accountCode short reference code used in dispatch and reports
 * @param legalName   legal registered name (falls back to legacy {@code name} if null)
 * @param active      false when the customer has been deactivated
 */
public record CustomerRef(
        UUID id,
        String accountCode,
        String legalName,
        String primaryContactName,
        boolean active
) {
}
