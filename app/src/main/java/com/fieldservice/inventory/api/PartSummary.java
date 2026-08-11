package com.fieldservice.inventory.api;

import com.fieldservice.inventory.domain.Part;

import java.time.Instant;
import java.util.UUID;

public record PartSummary(
        UUID   id,
        String partNumber,
        String name,
        String description,
        String unitOfMeasure,
        int    reorderPoint,
        int    reorderQuantity,
        boolean active,
        Instant createdAt) {

    public static PartSummary from(Part part) {
        return new PartSummary(
                part.getId(),
                part.getPartNumber(),
                part.getName(),
                part.getDescription(),
                part.getUnitOfMeasure(),
                part.getReorderPoint()    != null ? part.getReorderPoint()    : 0,
                part.getReorderQuantity() != null ? part.getReorderQuantity() : 0,
                Boolean.TRUE.equals(part.getActive()),
                part.getCreatedAt());
    }
}
