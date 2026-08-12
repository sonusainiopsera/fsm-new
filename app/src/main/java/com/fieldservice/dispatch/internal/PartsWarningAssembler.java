package com.fieldservice.dispatch.internal;

import com.fieldservice.dispatch.web.dto.PartsShortfallEntry;
import com.fieldservice.dispatch.web.dto.PartsWarning;
import com.fieldservice.inventory.api.CandidateAvailabilityResult;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.inventory.api.PartsAvailabilityStatus;
import com.fieldservice.inventory.api.PartShortfall;
import com.fieldservice.inventory.api.RequiredPartQuantity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Assembles a {@link PartsWarning} for the recommendations meta and assignment pre-check.
 *
 * <p>A PARTS_UNAVAILABLE warning is produced when at least one required part cannot be
 * satisfied from ANY candidate vehicle in the pool. Empty requirements always produce
 * {@link Optional#empty()} (AC-2 edge case).
 */
@Component
public class PartsWarningAssembler {

    /**
     * Returns a PARTS_UNAVAILABLE warning if any required part is unavailable network-wide.
     *
     * @param required     list of required parts for this work order
     * @param partsResult  batch availability result from the inventory port
     * @return warning with per-part shortfall detail, or empty when no network shortfall exists
     */
    public Optional<PartsWarning> assemble(
            List<RequiredPartQuantity> required,
            PartsAvailabilityResult partsResult) {

        if (required == null || required.isEmpty()) {
            return Optional.empty();
        }
        if (partsResult.jobVerdict() == PartsAvailabilityStatus.FULLY_STOCKED) {
            return Optional.empty();
        }

        Set<UUID> allLocationIds = partsResult.byVehicleLocation().keySet();

        // For each part: collect which locations report a shortfall
        // and track the best (highest) available quantity seen across those locations
        Map<UUID, Set<UUID>> locationsWithShortfall = new HashMap<>();
        Map<UUID, PartShortfall> bestShortfallData = new HashMap<>();

        for (Map.Entry<UUID, CandidateAvailabilityResult> entry :
                partsResult.byVehicleLocation().entrySet()) {
            UUID locId = entry.getKey();
            for (PartShortfall sf : entry.getValue().shortfalls()) {
                locationsWithShortfall
                        .computeIfAbsent(sf.partId(), k -> new HashSet<>())
                        .add(locId);
                bestShortfallData.merge(sf.partId(), sf,
                        (a, b) -> a.available() >= b.available() ? a : b);
            }
        }

        // A part has a network-wide shortfall if every candidate location reports it short
        List<PartsShortfallEntry> networkShortfalls = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> entry : locationsWithShortfall.entrySet()) {
            UUID partId = entry.getKey();
            Set<UUID> shortLocations = entry.getValue();
            if (shortLocations.containsAll(allLocationIds)) {
                PartShortfall best = bestShortfallData.get(partId);
                int onHand = best.available();
                int shortfall = Math.max(0, best.requested() - onHand);
                networkShortfalls.add(new PartsShortfallEntry(
                        partId, best.partNumber(), best.requested(), onHand, shortfall));
            }
        }

        if (networkShortfalls.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PartsWarning(PartsWarning.PARTS_UNAVAILABLE, networkShortfalls));
    }
}
