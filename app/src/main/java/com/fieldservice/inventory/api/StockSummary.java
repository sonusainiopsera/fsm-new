package com.fieldservice.inventory.api;

import com.fieldservice.inventory.domain.StockBalance;

import java.util.UUID;

public record StockSummary(
        UUID id,
        UUID partId,
        UUID stockLocationId,
        int  quantityOnHand,
        int  quantityReserved,
        int  quantityAvailable) {

    public static StockSummary from(StockBalance balance) {
        int onHand   = balance.getQuantityOnHand()   != null ? balance.getQuantityOnHand()   : 0;
        int reserved = balance.getQuantityReserved() != null ? balance.getQuantityReserved() : 0;
        return new StockSummary(
                balance.getId(),
                balance.getPartId(),
                balance.getLocationId(),
                onHand,
                reserved,
                Math.max(0, onHand - reserved));
    }
}
