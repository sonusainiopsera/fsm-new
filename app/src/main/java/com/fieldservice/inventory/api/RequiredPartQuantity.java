package com.fieldservice.inventory.api;

import java.util.UUID;

/**
 * A single line item in a parts requirement list: a part and the quantity needed.
 *
 * <p>Zero-quantity lines are ignored by the availability lookup (edge-case guard AC-3).
 *
 * @param partId           identifier of the required part
 * @param requiredQuantity quantity needed; must be positive (zero lines are discarded)
 */
public record RequiredPartQuantity(UUID partId, int requiredQuantity) {

    public RequiredPartQuantity {
        if (partId == null) throw new IllegalArgumentException("partId must not be null");
        if (requiredQuantity < 0) throw new IllegalArgumentException("requiredQuantity must be >= 0");
    }
}
