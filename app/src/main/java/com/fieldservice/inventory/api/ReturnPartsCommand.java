package com.fieldservice.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Command for returning unused parts from a work order back to a vehicle stock location.
 *
 * @param workOrderId  the work order the parts are being returned from
 * @param locationId   the vehicle stock location to return parts to
 * @param actorUserId  the authenticated technician performing the return
 * @param lines        one or more part lines to return; must not be empty
 */
public record ReturnPartsCommand(
        UUID workOrderId,
        UUID locationId,
        UUID actorUserId,
        List<ReturnLine> lines
) {

    /**
     * A single line in a return command.
     *
     * @param partId     the part to return
     * @param quantity   positive quantity to return (added back to stock)
     * @param reasonCode structured reason code (e.g. "UNUSED", "OVER_ORDERED")
     */
    public record ReturnLine(
            UUID partId,
            int quantity,
            String reasonCode
    ) {}

    public ReturnPartsCommand {
        lines = List.copyOf(lines);
    }
}
