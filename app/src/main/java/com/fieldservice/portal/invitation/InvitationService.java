package com.fieldservice.portal.invitation;

import com.fieldservice.platform.exception.NotFoundException;
import com.fieldservice.platform.util.UuidV7;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalAccountUserRepository;
import com.fieldservice.portal.domain.PortalInvitation;
import com.fieldservice.portal.domain.PortalInvitationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Manages portal invitation issuance and activation (WO-169, AC-8).
 *
 * <h3>Token lifecycle</h3>
 * <ol>
 *   <li>ADMIN or DISPATCHER calls {@link #issueInvitation} — generates a 256-bit
 *       SecureRandom token, stores only its SHA-256 hex digest, returns the raw token
 *       once (caller must deliver it to the invitee out-of-band).</li>
 *   <li>Invitee calls {@link #activateInvitation} with the raw token — token hash
 *       is verified, expiry and single-use guards are checked, a ACTIVE
 *       {@link PortalAccountUser} linkage row is created, and the invitation is
 *       marked consumed.</li>
 * </ol>
 *
 * <p>The raw token is <strong>never</strong> stored or logged; only the hash persists.
 */
@Service
@Transactional
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private static final int TOKEN_BYTES = 32;
    private static final String HASH_ALGORITHM = "SHA-256";

    private final PortalInvitationRepository invitationRepository;
    private final PortalAccountUserRepository accountUserRepository;
    private final Clock clock;
    private final Duration invitationTtl;

    public InvitationService(
            PortalInvitationRepository invitationRepository,
            PortalAccountUserRepository accountUserRepository,
            Clock clock,
            @Value("${portal.invitation.ttl-hours:72}") int ttlHours) {
        this.invitationRepository = invitationRepository;
        this.accountUserRepository = accountUserRepository;
        this.clock = clock;
        this.invitationTtl = Duration.ofHours(ttlHours);
    }

    /**
     * Issues a portal invitation for a contact against a customer account.
     *
     * <p>Only ADMIN and DISPATCHER may issue invitations.
     *
     * @param accountId    the customer account to link the invitee to
     * @param contactEmail invitee's email address (encrypted at rest)
     * @param contactName  invitee's display name (encrypted at rest); may be null
     * @return the raw invitation token — deliver to the invitee out-of-band; not stored
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
    public IssuedInvitation issueInvitation(UUID accountId, String contactEmail, String contactName) {
        String rawToken = generateRawToken();
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now(clock).plus(invitationTtl);

        PortalInvitation invitation = new PortalInvitation(
                accountId, contactEmail, contactName, tokenHash, expiresAt);
        invitationRepository.save(invitation);

        log.info("portal.invitation.issued: invitationId={} accountId={} expiresAt={}",
                invitation.getId(), accountId, expiresAt);
        // contactEmail intentionally not logged (Confidential per BR-23)

        return new IssuedInvitation(invitation.getId(), rawToken, expiresAt);
    }

    /**
     * Activates a portal invitation and creates the {@link PortalAccountUser} linkage row.
     *
     * <p>This operation is intentionally unauthenticated so invitees can activate before
     * they have a portal session. The token is a sufficient credential for this single operation.
     *
     * @param rawToken the raw token sent to the invitee (never stored)
     * @param userId   the app_user.id that will be linked to the customer account
     * @return the created linkage row
     * @throws NotFoundException         if the token hash is not found
     * @throws InvitationExpiredException if the invitation has expired
     * @throws InvitationConsumedExceptio if the invitation was already used
     */
    @PreAuthorize("permitAll()")
    public PortalAccountUser activateInvitation(String rawToken, UUID userId) {
        String tokenHash = hashToken(rawToken);
        Instant now = Instant.now(clock);

        PortalInvitation invitation = invitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new NotFoundException("Invitation not found"));

        if (invitation.isExpired(now)) {
            throw new InvitationExpiredException("Invitation has expired");
        }
        if (invitation.isConsumed()) {
            throw new InvitationConsumedException("Invitation has already been used");
        }

        invitation.consume(now);
        invitationRepository.save(invitation);

        PortalAccountUser linkage = new PortalAccountUser(userId, invitation.getAccountId());
        linkage.activate(now);
        accountUserRepository.save(linkage);

        log.info("portal.invitation.activated: invitationId={} userId={} accountId={}",
                invitation.getId(), userId, invitation.getAccountId());

        return linkage;
    }

    // -------------------------------------------------------------------------
    // Static helpers — package-private for unit testing
    // -------------------------------------------------------------------------

    static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -------------------------------------------------------------------------
    // Result and exception types
    // -------------------------------------------------------------------------

    public record IssuedInvitation(UUID invitationId, String rawToken, Instant expiresAt) {}

    public static class InvitationExpiredException extends RuntimeException {
        public InvitationExpiredException(String msg) { super(msg); }
    }

    public static class InvitationConsumedException extends RuntimeException {
        public InvitationConsumedException(String msg) { super(msg); }
    }
}
