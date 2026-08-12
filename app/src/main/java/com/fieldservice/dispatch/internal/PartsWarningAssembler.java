package com.fieldservice.dispatch.internal;

import com.fieldservice.inventory.api.AvailabilityStatus;
import com.fieldservice.inventory.api.CandidateAvailability;
import com.fieldservice.inventory.api.PartShortfall;
import com.fieldservice.inventory.api.PartsAvailabilityResult;
import com.fieldservice.workorder.web.AssignmentWarning;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles advisory {@link AssignmentWarning} objects from a {@link PartsAvailabilityResult}.
 *
 * <p>Warnings are advisory only — they must never block assignment or exclude any candidate
 * from the recommendation set. The assembler derives a network-wide worst-case status by
 * inspecting every vehicle location in the result and promoting the most severe status.
 *
 * <p>A warning is produced whenever the worst-case status is not {@code FULLY_STOCKED}.
 * No warning is produced when the required-parts set is empty or availability data is absent.
 */
@Component
public class PartsWarningAssembler {

    /**
     * Derives a single advisory warning from the worst-case availability across all
     * vehicle locations in {@code result}.
     *
     * @param result parts availability result from the inventory port; may be a degraded result
     * @return a list containing at most one {@link AssignmentWarning}, empty when all candidates
     *         are fully stocked or when there are no required parts
     */
    public List<AssignmentWarning> assemble(PartsAvailabilityResult result) {
        if (result == null || result.byLocationId().isEmpty()) {
            return List.of();
        }

        AvailabilityStatus worst = AvailabilityStatus.FULLY_STOCKED;
        CandidateAvailability worstCandidate = null;

        for (CandidateAvailability av : result.byLocationId().values()) {
            if (severity(av.status()) > severity(worst)) {
                worst = av.status();
                worstCandidate = av;
            }
        }

        if (worst == AvailabilityStatus.FULLY_STOCKED || worstCandidate == null) {
            return List.of();
        }

        List<PartShortfall> shortfalls = worstCandidate.shortfalls();

        return switch (worst) {
            case UNAVAILABLE -> List.of(new AssignmentWarning(
                    AssignmentWarning.CODE_UNAVAILABLE,
                    "One or more required parts are unavailable on vehicles or at any warehouse",
                    shortfalls));
            case PARTIALLY_STOCKED -> List.of(new AssignmentWarning(
                    AssignmentWarning.CODE_PARTIALLY_STOCKED,
                    "Some required parts are only partially available across all vehicle and warehouse locations",
                    shortfalls));
            case COLLECTABLE -> List.of(new AssignmentWarning(
                    AssignmentWarning.CODE_COLLECTABLE,
                    "Required parts are not on any vehicle but can be collected from a warehouse",
                    shortfalls));
            default -> List.of();
        };
    }

    private static int severity(AvailabilityStatus status) {
        return switch (status) {
            case FULLY_STOCKED    -> 0;
            case COLLECTABLE      -> 1;
            case PARTIALLY_STOCKED -> 2;
            case UNAVAILABLE      -> 3;
        };
    }
}
