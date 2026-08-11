package com.fieldservice.fixtures;

import com.fieldservice.technician.domain.Technician;
import com.fieldservice.technician.domain.TechnicianCertification;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Object-mother builders for {@link Technician} and {@link TechnicianCertification}.
 *
 * <p>Phone numbers use the reserved {@code 555} prefix.
 * Certification codes use the reserved {@code CERT-} namespace.
 */
public final class TechnicianFixtures {

    public static final String CERT_HIGH_VOLTAGE   = "CERT-HV-001";
    public static final String CERT_CONFINED_SPACE = "CERT-CS-002";
    public static final String CERT_ELECTRICAL_LV  = "CERT-EL-003";

    private TechnicianFixtures() {}

    // ---- Technician builder --------------------------------------------------

    public static final class TechnicianBuilder {

        private UUID   id       = DeterministicIds.next();
        private UUID   userId;
        private String fullName;
        private String phone    = "555-010-0001";

        private TechnicianBuilder(UUID userId, String fullName) {
            this.userId   = userId;
            this.fullName = fullName;
        }

        public TechnicianBuilder withId(UUID id)           { this.id = id;             return this; }
        public TechnicianBuilder withPhone(String phone)   { this.phone = phone;       return this; }
        public TechnicianBuilder withFullName(String name) { this.fullName = name;     return this; }

        public Technician build() {
            Technician t = new Technician(userId, fullName);
            setField(Technician.class, t, "id", id);
            setField(Technician.class, t, "phone", phone);
            return t;
        }
    }

    // ---- TechnicianCertification builder ------------------------------------

    public static final class CertificationBuilder {

        private UUID    id           = DeterministicIds.next();
        private UUID    technicianId;
        private String  certCode;
        private Instant issuedAt     = DeterministicIds.FIXED_INSTANT.minus(365, ChronoUnit.DAYS);
        private Instant expiresAt;   // null = no expiry

        private CertificationBuilder(UUID technicianId, String certCode) {
            this.technicianId = technicianId;
            this.certCode     = certCode;
        }

        public CertificationBuilder withId(UUID id)           { this.id = id;         return this; }
        public CertificationBuilder withIssuedAt(Instant ts)  { issuedAt = ts;        return this; }
        public CertificationBuilder withExpiresAt(Instant ts) { expiresAt = ts;       return this; }
        public CertificationBuilder noExpiry()                { expiresAt = null;     return this; }

        /** Expiry is yesterday relative to the fixed clock — treated as expired. */
        public CertificationBuilder expiredYesterday() {
            expiresAt = DeterministicIds.FIXED_INSTANT.minus(1, ChronoUnit.DAYS);
            return this;
        }

        /** Expiry is tomorrow relative to the fixed clock — still valid. */
        public CertificationBuilder expiringTomorrow() {
            expiresAt = DeterministicIds.FIXED_INSTANT.plus(1, ChronoUnit.DAYS);
            return this;
        }

        /** Expiry is 30 days ahead — valid but expiring soon. */
        public CertificationBuilder expiringSoon() {
            expiresAt = DeterministicIds.FIXED_INSTANT.plus(30, ChronoUnit.DAYS);
            return this;
        }

        /** Expiry is exactly at the fixed clock instant — boundary, treated as expired. */
        public CertificationBuilder expiresAtFixedInstant() {
            expiresAt = DeterministicIds.FIXED_INSTANT;
            return this;
        }

        public TechnicianCertification build() {
            TechnicianCertification cert = new TechnicianCertification(
                    technicianId, certCode, issuedAt, expiresAt);
            setField(TechnicianCertification.class, cert, "id", id);
            return cert;
        }
    }

    // ---- Static factories ---------------------------------------------------

    public static TechnicianBuilder defaults(UUID userId) {
        return new TechnicianBuilder(userId, "Field Technician");
    }

    public static TechnicianBuilder named(UUID userId, String fullName) {
        return new TechnicianBuilder(userId, fullName);
    }

    /** Currently valid certification — expires 30 days after the fixed instant. */
    public static CertificationBuilder validCertification(UUID technicianId) {
        return new CertificationBuilder(technicianId, CERT_HIGH_VOLTAGE).expiringSoon();
    }

    /** Certification with no explicit expiry date — always valid. */
    public static CertificationBuilder nonExpiringCertification(UUID technicianId) {
        return new CertificationBuilder(technicianId, CERT_HIGH_VOLTAGE).noExpiry();
    }

    /** Certification expired one day before the fixed instant. */
    public static CertificationBuilder expiredCertification(UUID technicianId) {
        return new CertificationBuilder(technicianId, CERT_HIGH_VOLTAGE).expiredYesterday();
    }

    /** Certification expiring exactly at the fixed instant — boundary expired case. */
    public static CertificationBuilder boundaryExpiredCertification(UUID technicianId) {
        return new CertificationBuilder(technicianId, CERT_HIGH_VOLTAGE).expiresAtFixedInstant();
    }

    // ---- Reflection helper --------------------------------------------------

    static void setField(Class<?> cls, Object target, String name, Object value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "Fixture reflection failed: " + cls.getSimpleName() + "." + name, e);
        }
    }
}
