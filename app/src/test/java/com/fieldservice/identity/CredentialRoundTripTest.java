package com.fieldservice.identity;

import com.fieldservice.domain.user.AppUser;
import com.fieldservice.security.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip test ensuring {@code password_hash} survives persistence without truncation.
 *
 * <p>BCrypt produces exactly 60 characters in the format
 * {@code $2a$<cost>$<22-char salt><31-char hash>}. A column sized to fewer than 60
 * characters silently truncates the hash, causing authentication to fail non-obviously.
 *
 * <p>Tests two variants:
 * <ol>
 *   <li>A bare 60-character BCrypt hash (no prefix).</li>
 *   <li>An algorithm-prefixed variant ({@code $bcrypt$}+hash) to prove the widened
 *       column accommodates future multi-algorithm deployments.</li>
 * </ol>
 */
@DisplayName("Password hash round-trip and column-width tests")
class CredentialRoundTripTest extends AbstractIntegrationTest {

    // A real-format BCrypt hash: $2a$10$<22-char salt><31-char hash> = 60 chars
    private static final String BCRYPT_60 =
            "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ12345";

    // Algorithm-prefixed variant for future multi-algorithm deployment
    private static final String ALGO_PREFIXED = "$bcrypt$" + BCRYPT_60;

    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate txTemplate;

    @Test
    @DisplayName("60-character BCrypt hash survives round-trip with byte-for-byte equality")
    void bcrypt60CharHash_roundTrip() {
        assertThat(BCRYPT_60).hasSize(60);

        UUID[] id = {null};
        txTemplate.executeWithoutResult(status -> {
            AppUser u = new AppUser();
            u.setEmail("bcrypt.rt." + UUID.randomUUID() + "@example.com");
            u.setPasswordHash(BCRYPT_60);
            u.setDisplayName("BCrypt RT");
            entityManager.persist(u);
            entityManager.flush();
            id[0] = u.getId();
        });

        txTemplate.executeWithoutResult(status -> {
            entityManager.clear(); // force reload from DB
            AppUser reloaded = entityManager.find(AppUser.class, id[0]);
            assertThat(reloaded.getPasswordHash())
                    .as("Password hash must be identical after persist + reload — no truncation")
                    .isEqualTo(BCRYPT_60);
        });
    }

    @Test
    @DisplayName("Algorithm-prefixed hash survives round-trip with byte-for-byte equality")
    void algorithmPrefixedHash_roundTrip() {
        UUID[] id = {null};
        txTemplate.executeWithoutResult(status -> {
            AppUser u = new AppUser();
            u.setEmail("algopfx.rt." + UUID.randomUUID() + "@example.com");
            u.setPasswordHash(ALGO_PREFIXED);
            u.setDisplayName("AlgoPfx RT");
            entityManager.persist(u);
            entityManager.flush();
            id[0] = u.getId();
        });

        txTemplate.executeWithoutResult(status -> {
            entityManager.clear();
            AppUser reloaded = entityManager.find(AppUser.class, id[0]);
            assertThat(reloaded.getPasswordHash())
                    .as("Algorithm-prefixed hash must be identical after reload — column must be wide enough")
                    .isEqualTo(ALGO_PREFIXED);
        });
    }

    @Test
    @DisplayName("Federated user with null password_hash persists successfully")
    void federatedUser_nullPasswordHash_persists() {
        UUID[] id = {null};
        txTemplate.executeWithoutResult(status -> {
            AppUser u = new AppUser();
            u.setEmail("federated." + UUID.randomUUID() + "@example.com");
            u.setPasswordHash(null);  // federated user — no local credential
            u.setExternalSubject("https://idp.example.com/users/fed-user-001");
            u.setDisplayName("Federated User");
            entityManager.persist(u);
            entityManager.flush();
            id[0] = u.getId();
        });

        txTemplate.executeWithoutResult(status -> {
            entityManager.clear();
            AppUser reloaded = entityManager.find(AppUser.class, id[0]);
            assertThat(reloaded.getPasswordHash()).isNull();
            assertThat(reloaded.getExternalSubject())
                    .isEqualTo("https://idp.example.com/users/fed-user-001");
        });
    }
}
