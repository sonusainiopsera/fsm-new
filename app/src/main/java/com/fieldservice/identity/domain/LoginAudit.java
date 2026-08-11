package com.fieldservice.identity.domain;

import com.fieldservice.platform.util.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record for a single login attempt.
 *
 * <p>Every attempt — success or failure — writes one row atomically with its
 * Envers revision and outbox event so authentication history is tamper-evident
 * and retained for at least a year.
 *
 * <p>PII masking: {@code emailHash} stores only the SHA-256 hex of the
 * canonicalized email address. Neither the raw email nor the submitted password
 * ever appears in this record, its revision, or its outbox event.
 */
@Audited
@Entity
@Table(name = "login_audit")
public class LoginAudit {

    @Id
    private UUID id;

    /** SHA-256 hex of lower(trim(email)) — exactly 64 lowercase hex characters. */
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    /** NULL when the email was not found (UNKNOWN_EMAIL outcome). */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "outcome", nullable = false, length = 30)
    private String outcome;

    @Column(name = "trace_id", length = 255)
    private String traceId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    protected LoginAudit() {}

    public static LoginAudit record(String emailHash, UUID userId,
                                    LoginOutcome outcome, String traceId, String clientIp) {
        LoginAudit a = new LoginAudit();
        a.id          = UuidV7.generate();
        a.emailHash   = emailHash;
        a.userId      = userId;
        a.outcome     = outcome.name();
        a.traceId     = traceId;
        a.clientIp    = clientIp;
        a.attemptedAt = Instant.now();
        return a;
    }

    public UUID    getId()          { return id; }
    public String  getEmailHash()   { return emailHash; }
    public UUID    getUserId()      { return userId; }
    public String  getOutcome()     { return outcome; }
    public String  getTraceId()     { return traceId; }
    public String  getClientIp()    { return clientIp; }
    public Instant getAttemptedAt() { return attemptedAt; }
}
