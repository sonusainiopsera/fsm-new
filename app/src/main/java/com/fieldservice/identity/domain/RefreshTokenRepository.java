package com.fieldservice.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    long countByFamilyIdAndConsumedAtIsNull(UUID familyId);

    /**
     * Atomically marks the token consumed using a conditional UPDATE.
     *
     * <p>Returns 1 if the token was unconsumed and has been consumed by this call;
     * returns 0 if the token was already consumed or the hash is unknown.
     * Uses {@code now()} from the database so clock skew cannot affect the result.
     * Must be called within an active transaction.
     */
    @Modifying
    @Query(value = "UPDATE refresh_token SET consumed_at = now() WHERE token_hash = :hash AND consumed_at IS NULL",
           nativeQuery = true)
    int consumeByTokenHash(@Param("hash") String hash);
}
