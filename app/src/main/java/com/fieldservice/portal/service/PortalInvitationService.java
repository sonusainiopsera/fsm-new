package com.fieldservice.portal.service;

import com.fieldservice.portal.domain.PortalAccountStatus;
import com.fieldservice.portal.domain.PortalAccountUser;
import com.fieldservice.portal.domain.PortalInvitation;
import com.fieldservice.portal.repository.PortalAccountUserRepository;
import com.fieldservice.portal.repository.PortalInvitationRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Manages the invitation lifecycle: issuance by ADMIN/DISPATCHER and activation
 * by an invited user.
 *
 * <h3>Token design</h3>
 * A 256-bit SecureRandom token is base64url-encoded for transport. Only the SHA-256 hex
 * digest of the token is stored in {@code portal_invitation.token_hash}. The plaintext
 * token is returned once at issuance and never stored.
 *
 * <h3>Single-use enforcement</h3>
 * Once consumed, the token cannot be replayed. Expired tokens, unknown tokens, and
 * consumed tokens all return an identical error so the existence of the invitation is
 * not disclosed.
 *
 * <h3>Activation</h3>
 * On successful activation, a new {@link PortalAccountUser} row is created with status
 * ACTIVE, and the invitation's {@code consumed_at} is set within the same transaction so
 * the Envers revision record covers both mutations.
 */
@Service
public class PortalInvitationService {

    static final int TOKEN_BYTES     = 32;
    static final int EXPIRY_HOURS    = 72;

    private final PortalInvitationRepository  invitationRepository;
    private final PortalAccountUserRepository accountUserRepository;
    private final Clock                       clock;

    public PortalInvitationService(
            PortalInvitationRepository  invitationRepository,
            PortalAccountUserRepository accountUserRepository,
            Clock                       clock) {
        this.invitationRepository  = invitationRepository;
        this.accountUserRepository = accountUserRepository;
        this.clock                 = clock;
    }

    // -------------------------------------------------------------------------
    // Issuance — ADMIN or DISPATCHER only
    // -------------------------------------------------------------------------

    /**
     * Issues a new invitation for the given account.
     *
     * @param accountId    customer account to link the invited user to
     * @param contactEmail Confidential — encrypted at rest, never logged
     * @param contactName  Confidential — encrypted at rest, never logged
     * @param issuedBy     userId of the issuing ADMIN/DISPATCHER
     * @return the result containing the invitation id, expiry time, and plaintext token
     *         (to be sent to the invitee exactly once)
     */
    @PreAuthorize("hasRole('ADMIN') or hasRole('DISPATCHER')")
    @Transactional
    public IssuanceResult issueInvitation(UUID accountId, String contactEmail,
                                          String contactName, UUID issuedBy) {
        String plainToken = generateToken();
        String tokenHash  = sha256Hex(plainToken);
        Instant expiresAt = clock.instant().plusSeconds(EXPIRY_HOURS * 3600L);

        PortalInvitation inv = new PortalInvitation(
                accountId, contactEmail, contactName, tokenHash, expiresAt, issuedBy);
        invitationRepository.save(inv);

        return new IssuanceResult(inv.getId(), expiresAt, plainToken);
    }

    // -------------------------------------------------------------------------
    // Activation — anonymous (newly invited user clicks the link)
    // -------------------------------------------------------------------------

    /**
     * Activates an invitation by its plaintext token, creating the portal account linkage.
     *
     * @param plainToken the plaintext token from the invitation link
     * @param userId     the identity user id to link to the account
     * @return the activation result containing the user id and account id
     * @throws InvitationNotFoundException if the token is unknown, expired, or consumed
     * @throws DuplicateLinkageException   if the user already has an active portal linkage
     */
    @Transactional
    public ActivationResult activateInvitation(String plainToken, UUID userId) {
        String tokenHash  = sha256Hex(plainToken);
        Instant now       = clock.instant();

        PortalInvitation inv = invitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvitationNotFoundException::new);

        if (!inv.isActive(now)) {
            throw new InvitationNotFoundException();
        }

        if (accountUserRepository.findByUserId(userId).isPresent()) {
            throw new DuplicateLinkageException(
                    "User already has a portal account linkage");
        }

        inv.consume(now);
        invitationRepository.save(inv);

        PortalAccountUser pau = new PortalAccountUser(
                userId, inv.getAccountId(), PortalAccountStatus.PENDING);
        pau.activate(now);
        accountUserRepository.save(pau);

        return new ActivationResult(userId, inv.getAccountId());
    }

    // -------------------------------------------------------------------------
    // Token utilities (package-visible for tests)
    // -------------------------------------------------------------------------

    static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 hex digest of the token.
     * Public and static so integration tests can compute the expected hash.
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -------------------------------------------------------------------------
    // Result records
    // -------------------------------------------------------------------------

    public record IssuanceResult(UUID invitationId, Instant expiresAt, String plainToken) {}

    public record ActivationResult(UUID userId, UUID accountId) {}

    // -------------------------------------------------------------------------
    // Domain exceptions
    // -------------------------------------------------------------------------

    /** Thrown for unknown, expired, or consumed invitation tokens (identical message). */
    public static final class InvitationNotFoundException extends RuntimeException {
        public InvitationNotFoundException() {
            super("Invitation not found or no longer valid");
        }
    }

    /** Thrown when the target user already has an active portal linkage. */
    public static final class DuplicateLinkageException extends RuntimeException {
        public DuplicateLinkageException(String message) { super(message); }
    }
}
