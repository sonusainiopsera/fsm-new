package com.fieldservice.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Result of a successful parts consumption operation.
 *
 * @param workOrderId         the work order the parts were logged against
 * @param loggedLines         one entry per applied consumption line
 * @param reconciliationStatus "PENDING" in this release; "RECONCILED" set by closing guard
 */
public record ConsumePartsResult(
        UUID workOrderId,
        List<LoggedLine> loggedLines,
        String reconciliationStatus
) {

    /**
     * Result detail for one consumed part line.
     *
     * @param partId               the part consumed
     * @param partNumber           the human-readable part number
     * @param quantity             quantity consumed (positive)
     * @param resultingQuantityOnHand balance after consumption
     */
    public record LoggedLine(
            UUID partId,
            String partNumber,
            int quantity,
            int resultingQuantityOnHand
    ) {}
}
