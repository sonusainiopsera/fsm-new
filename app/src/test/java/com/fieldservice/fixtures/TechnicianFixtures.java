package com.fieldservice.fixtures;

import com.fieldservice.domain.technician.Technician;
import com.fieldservice.domain.technician.TechnicianCertification;
import com.fieldservice.domain.user.AppUser;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Object-mother for {@link Technician} and {@link TechnicianCertification} test fixtures.
 *
 * <p>Phone numbers use the NANP reserved {@code +15555550xxx} range. All certifications
 * are synthetic and reference the fixed {@link DeterministicIds#EPOCH} clock.
 *
 * <p>Certification validity relative to {@link DeterministicIds#EPOCH}:
 * <ul>
 *   <li><em>Current</em>: expires 365 days after EPOCH — clearly valid at EPOCH.</li>
 *   <li><em>Expiring-soon</em>: expires 7 days after EPOCH — within the 30-day warning window.</li>
 *   <li><em>Expired-yesterday</em>: expires 1 day before EPOCH — fails eligibility at EPOCH.</li>
 *   <li><em>Revoked</em>: never-expired but {@code is_revoked = true}.</li>
 * </ul>
 */
public final class TechnicianFixtures {

    private TechnicianFixtures() {}

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /** A fully valid, active technician with a current HVAC certification. */
    public static Builder active() {
        return new Builder()
                .withEmployeeNo("EMP-FX-001")
                .withPhone("+15555550001")
                .withActive(true)
                .withCertification(CertificationTemplate.CURRENT_HVAC);
    }

    /** An active technician whose only certification expires in 7 days (expiring-soon). */
    public static Builder expiringSoon() {
        return new Builder()
                .withEmployeeNo("EMP-FX-002")
                .withPhone("+15555550002")
                .withActive(true)
                .withCertification(CertificationTemplate.EXPIRING_SOON_HVAC);
    }

    /** An active technician whose only certification expired yesterday (ineligible at EPOCH). */
    public static Builder expiredCertification() {
        return new Builder()
                .withEmployeeNo("EMP-FX-003")
                .withPhone("+15555550003")
                .withActive(true)
                .withCertification(CertificationTemplate.EXPIRED_HVAC);
    }

    /** An inactive (deactivated) technician — no current jobs should be assigned. */
    public static Builder inactive() {
        return new Builder()
                .withEmployeeNo("EMP-FX-004")
                .withPhone("+15555550004")
                .withActive(false);
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static final class Builder {

        private UUID id = DeterministicIds.nextId();
        private AppUser user;
        private String employeeNo = "EMP-FX-000";
        private String phone = "+15555550000";
        private boolean active = true;
        private final List<CertificationTemplate> certTemplates = new ArrayList<>();

        public Builder withId(UUID id)                         { this.id = id;             return this; }
        public Builder withUser(AppUser user)                  { this.user = user;         return this; }
        public Builder withEmployeeNo(String no)               { this.employeeNo = no;     return this; }
        public Builder withPhone(String phone)                 { this.phone = phone;       return this; }
        public Builder withActive(boolean active)              { this.active = active;     return this; }

        public Builder withCertification(CertificationTemplate template) {
            certTemplates.add(template);
            return this;
        }

        /** Builds the {@link Technician} linked to the supplied {@code user}. */
        public TechnicianWithCerts build(AppUser user) {
            Technician tech = new Technician();
            tech.setId(id);
            tech.setUser(user);
            tech.setEmployeeNo(employeeNo);
            tech.setPhone(phone);
            tech.setActive(active);

            List<TechnicianCertification> certs = certTemplates.stream()
                    .map(t -> t.build(id))
                    .toList();

            return new TechnicianWithCerts(tech, certs);
        }

        /**
         * Builds the {@link Technician} using the {@link #user} set via {@link #withUser(AppUser)}.
         * Throws if {@link #withUser(AppUser)} was not called.
         */
        public TechnicianWithCerts build() {
            if (user == null) throw new IllegalStateException("Call withUser() before build()");
            return build(user);
        }
    }

    // -----------------------------------------------------------------------
    // Certification template
    // -----------------------------------------------------------------------

    /** Reusable certification templates anchored to {@link DeterministicIds#EPOCH}. */
    public enum CertificationTemplate {

        CURRENT_HVAC("HVAC", "CERT-HVAC-001",
                DeterministicIds.EPOCH.minus(Duration.ofDays(180)),
                DeterministicIds.EPOCH.plus(Duration.ofDays(365)),
                false),

        EXPIRING_SOON_HVAC("HVAC", "CERT-HVAC-EXP-007",
                DeterministicIds.EPOCH.minus(Duration.ofDays(358)),
                DeterministicIds.EPOCH.plus(Duration.ofDays(7)),
                false),

        /** Expired exactly 1 day before EPOCH — ineligible at EPOCH boundary. */
        EXPIRED_HVAC("HVAC", "CERT-HVAC-EXP-NEG1",
                DeterministicIds.EPOCH.minus(Duration.ofDays(366)),
                DeterministicIds.EPOCH.minus(Duration.ofDays(1)),
                false),

        REVOKED_ELECTRICAL("ELECTRICAL", "CERT-ELEC-REV-001",
                DeterministicIds.EPOCH.minus(Duration.ofDays(90)),
                DeterministicIds.EPOCH.plus(Duration.ofDays(275)),
                true),

        CURRENT_ELECTRICAL("ELECTRICAL", "CERT-ELEC-001",
                DeterministicIds.EPOCH.minus(Duration.ofDays(60)),
                DeterministicIds.EPOCH.plus(Duration.ofDays(305)),
                false),

        CURRENT_PLUMBING("PLUMBING", "CERT-PLMB-001",
                DeterministicIds.EPOCH.minus(Duration.ofDays(30)),
                DeterministicIds.EPOCH.plus(Duration.ofDays(335)),
                false);

        private final String certType;
        private final String certReference;
        private final Instant issuedAt;
        private final Instant expiresAt;
        private final boolean revoked;

        CertificationTemplate(String certType, String certReference,
                              Instant issuedAt, Instant expiresAt, boolean revoked) {
            this.certType = certType;
            this.certReference = certReference;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.revoked = revoked;
        }

        TechnicianCertification build(UUID technicianId) {
            TechnicianCertification cert = new TechnicianCertification();
            // TechnicianCertification does not extend BaseEntity so has no setId();
            // ID is generated by Hibernate on persist. Key fields are deterministic.
            cert.setTechnicianId(technicianId);
            cert.setCertType(certType);
            cert.setCertReference(certReference);
            cert.setIssuedAt(issuedAt);
            cert.setExpiresAt(expiresAt);
            cert.setRevoked(revoked);
            return cert;
        }
    }

    // -----------------------------------------------------------------------
    // Value carrier
    // -----------------------------------------------------------------------

    public record TechnicianWithCerts(Technician technician, List<TechnicianCertification> certifications) {
        public TechnicianWithCerts {
            certifications = Collections.unmodifiableList(new ArrayList<>(certifications));
        }
    }
}
