package com.fieldservice.catalog.application;

/** Immutable command object for creating or updating a customer. */
public record CreateCustomerCommand(
        String accountCode,
        String legalName,
        String primaryContactName,
        String primaryContactEmail,
        String primaryContactPhone,
        String billingAddress
) {
}
