package com.fieldservice.fixtures;

import com.fieldservice.domain.technician.Technician;
import com.fieldservice.domain.technician.TechnicianCertification;
import com.fieldservice.domain.user.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TechnicianFixtures unit tests")
class TechnicianFixturesTest {

    @BeforeEach
    void reset() {
        DeterministicIds.resetSequence();
    }

    @Test
    @DisplayName("active() produces enabled technician with a current HVAC certification")
    void active_producesActiveWithCurrentCert() {
        AppUser user = UserFixtures.technician().build();
        TechnicianFixtures.TechnicianWithCerts result = TechnicianFixtures.active().build(user);

        Technician tech = result.technician();
        assertThat(tech.isActive()).isTrue();
        assertThat(tech.getEmployeeNo()).isNotBlank();
        assertThat(tech.getUser()).isEqualTo(user);

        List<TechnicianCertification> certs = result.certifications();
        assertThat(certs).hasSize(1);
        TechnicianCertification cert = certs.get(0);
        assertThat(cert.getCertType()).isEqualTo("HVAC");
        assertThat(cert.isRevoked()).isFalse();
        // Current cert: expires after EPOCH
        assertThat(cert.getExpiresAt()).isAfter(DeterministicIds.EPOCH);
    }

    @Test
    @DisplayName("expiredCertification() has expires_at exactly 1 day before EPOCH")
    void expiredCertification_expiresBeforeEpoch() {
        AppUser user = UserFixtures.technician().build();
        List<TechnicianCertification> certs =
                TechnicianFixtures.expiredCertification().build(user).certifications();

        assertThat(certs).hasSize(1);
        Instant expiresAt = certs.get(0).getExpiresAt();
        // Must be expired at EPOCH (i.e. expiresAt is before EPOCH)
        assertThat(expiresAt).isBefore(DeterministicIds.EPOCH);
    }

    @Test
    @DisplayName("expiringSoon() expires within 30 days after EPOCH")
    void expiringSoon_expiresWithin30Days() {
        AppUser user = UserFixtures.technician().build();
        TechnicianCertification cert =
                TechnicianFixtures.expiringSoon().build(user).certifications().get(0);

        assertThat(cert.getExpiresAt()).isAfter(DeterministicIds.EPOCH);
        assertThat(cert.getExpiresAt()).isBefore(DeterministicIds.EPOCH.plusSeconds(30 * 24 * 3600));
    }

    @Test
    @DisplayName("Expired certification — boundary: expires exactly at EPOCH minus 1 day")
    void expiredCertification_boundary_dayBeforeEpoch() {
        AppUser user = UserFixtures.technician().build();
        TechnicianCertification cert =
                TechnicianFixtures.expiredCertification().build(user).certifications().get(0);

        Instant expiresAt = cert.getExpiresAt();
        // Should be 1 day before epoch: EPOCH - 24h
        assertThat(expiresAt).isEqualTo(DeterministicIds.EPOCH.minusSeconds(86400));
    }

    @Test
    @DisplayName("inactive() produces a deactivated technician")
    void inactive_producesDeactivated() {
        AppUser user = UserFixtures.technician().build();
        Technician tech = TechnicianFixtures.inactive().build(user).technician();
        assertThat(tech.isActive()).isFalse();
    }

    @Test
    @DisplayName("Phone numbers use reserved +15555550xxx range")
    void phoneNumbers_useReservedRange() {
        AppUser user = UserFixtures.technician().build();
        String phone = TechnicianFixtures.active().build(user).technician().getPhone();
        assertThat(phone).startsWith("+1555555");
    }

    @Test
    @DisplayName("Technician with multiple certs returns all certs")
    void multipleCerts_allReturned() {
        AppUser user = UserFixtures.technician().build();
        TechnicianFixtures.TechnicianWithCerts result = TechnicianFixtures.active()
                .withCertification(TechnicianFixtures.CertificationTemplate.CURRENT_ELECTRICAL)
                .withCertification(TechnicianFixtures.CertificationTemplate.CURRENT_PLUMBING)
                .build(user);

        // 1 from active() default + 2 added = 3
        assertThat(result.certifications()).hasSize(3);
    }

    @Test
    @DisplayName("Technician with expired HVAC and current ELECTRICAL: one is eligible, one is not")
    void mixedCerts_eligibilityDistinct() {
        AppUser user = UserFixtures.technician().build();
        TechnicianFixtures.TechnicianWithCerts result = new TechnicianFixtures.Builder()
                .withEmployeeNo("DS-MIXED-001")
                .withPhone("+15555550099")
                .withCertification(TechnicianFixtures.CertificationTemplate.EXPIRED_HVAC)
                .withCertification(TechnicianFixtures.CertificationTemplate.CURRENT_ELECTRICAL)
                .build(user);

        List<TechnicianCertification> certs = result.certifications();
        assertThat(certs).hasSize(2);

        long expiredCount = certs.stream()
                .filter(c -> c.getExpiresAt() != null && c.getExpiresAt().isBefore(DeterministicIds.EPOCH))
                .count();
        long currentCount = certs.stream()
                .filter(c -> c.getExpiresAt() == null || c.getExpiresAt().isAfter(DeterministicIds.EPOCH))
                .count();

        assertThat(expiredCount).isEqualTo(1); // HVAC expired
        assertThat(currentCount).isEqualTo(1); // ELECTRICAL current
    }
}
