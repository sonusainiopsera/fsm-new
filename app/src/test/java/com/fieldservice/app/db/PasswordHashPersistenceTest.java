package com.fieldservice.app.db;

import com.fieldservice.app.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-6: Persists and reads back a full 60-character BCrypt hash to prove
 * password_hash VARCHAR(72) does not silently truncate.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordHashPersistenceTest extends AbstractIntegrationTest {

    // Exactly 60 characters — matches the BCrypt $2a$10$<22-char-salt><31-char-hash> format.
    private static final String SIXTY_CHAR_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("60-character BCrypt hash persists and reads back without truncation")
    @Transactional
    void sixty_char_bcrypt_hash_round_trips_without_truncation() {
        assertThat(SIXTY_CHAR_HASH).hasSize(60);

        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)",
                userId, "hashtest@fieldservice.example", SIXTY_CHAR_HASH, "Hash Test User");

        String read = jdbc.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?",
                String.class, userId);

        assertThat(read)
                .as("password_hash must round-trip exactly 60 characters without truncation")
                .isEqualTo(SIXTY_CHAR_HASH)
                .hasSize(60);
    }
}
