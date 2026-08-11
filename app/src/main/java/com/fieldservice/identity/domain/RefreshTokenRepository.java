package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    /**
     * Atomically marks the token identified by {@code tokenHash} as consumed,
     * returning 1 if the token was found unconsumed and unrevoked, or 0 otherwise.
     *
     * <p>An affected-row count of 0 means either the token hash is unknown, the token
     * was already consumed (reuse), or the token was revoked — all treated as reuse.
     * Using a conditional UPDATE avoids a read-then-write race under concurrency.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE refresh_token SET consumed_at = NOW() " +
                   "WHERE token_hash = :tokenHash AND consumed_at IS NULL AND revoked_at IS NULL",
           nativeQuery = true)
    int consumeToken(@Param("tokenHash") String tokenHash);
}
