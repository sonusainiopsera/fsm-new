package com.fieldservice.dispatch.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minimal technician snapshot for scoring context assembly.
 */
record TechnicianScoringData(
        UUID technicianId,
        String displayName,
        Double homeLatitude,
        Double homeLongitude,
        List<String> certificationCodes,
        double bookedHours,
        UUID vehicleStockLocationId
) {
    TechnicianScoringData {
        certificationCodes = certificationCodes == null ? List.of() : List.copyOf(certificationCodes);
    }

    static final class Builder {
        private final UUID technicianId;
        private final String displayName;
        private final Double homeLatitude;
        private final Double homeLongitude;
        private final List<String> certificationCodes = new ArrayList<>();
        private double bookedHours = 0.0;
        private UUID vehicleStockLocationId;

        Builder(UUID technicianId, String displayName, Double homeLatitude, Double homeLongitude) {
            this.technicianId = technicianId;
            this.displayName = displayName;
            this.homeLatitude = homeLatitude;
            this.homeLongitude = homeLongitude;
        }

        void addCert(String code) { certificationCodes.add(code); }
        void bookedHours(double h) { this.bookedHours = h; }
        void vehicleStockLocationId(UUID id) { this.vehicleStockLocationId = id; }

        TechnicianScoringData build() {
            return new TechnicianScoringData(technicianId, displayName, homeLatitude, homeLongitude,
                    certificationCodes, bookedHours, vehicleStockLocationId);
        }
    }
}
