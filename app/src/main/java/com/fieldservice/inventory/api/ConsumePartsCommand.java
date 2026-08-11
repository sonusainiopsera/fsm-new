package com.fieldservice.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Command for logging parts consumption against a work order from a vehicle stock location.
 *
 * @param workOrderId   the work order being serviced
 * @param locationId    the vehicle stock location lines are consumed from
 * @param actorUserId   the authenticated technician performing the consumption
 * @param lines         one or more part lines to consume; must not be empty
 */
public record ConsumePartsCommand(
        UUID workOrderId,
        UUID locationId,
        UUID actorUserId,
        List<ConsumptionLine> lines
) {

    /**
     * A single line in a consumption command.
     *
     * @param partId     the part to consume
     * @param quantity   positive quantity to deduct; must be &gt; 0
     * @param reasonCode structured reason code (e.g. "USED_ON_JOB")
     */
    public record ConsumptionLine(
            UUID partId,
            int quantity,
            String reasonCode
    ) {}

    public ConsumePartsCommand {
        lines = List.copyOf(lines);
    }
}
