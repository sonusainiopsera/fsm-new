package com.fieldservice.catalog.application;

import java.time.LocalDate;

/** Immutable command object for creating an asset under a site. */
public record CreateAssetCommand(
        String assetTag,
        String manufacturer,
        String model,
        String serialNumber,
        String category,
        LocalDate installedOn
) {
}
