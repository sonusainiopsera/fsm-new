package com.fieldservice.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.io.Serializable;
import java.time.Instant;

/**
 * Custom Envers revision entity stored in the {@code revinfo} table.
 *
 * <p>Extends the default two-column REVINFO with actor attribution required for SOC 2 / ISO 27001:
 * the authenticated user id, their primary role, the request trace id, and the client IP address.
 *
 * <p>When no authenticated principal is present (worker profile, scheduled jobs, migrations),
 * {@link AuditRevisionListener} records a documented synthetic {@code system} actor.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(AuditRevisionListener.class)
public class AuditRevisionEntity implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revinfo_seq")
    @SequenceGenerator(name = "revinfo_seq", sequenceName = "revinfo_seq", allocationSize = 1)
    @RevisionNumber
    @Column(name = "rev")
    private int rev;

    @RevisionTimestamp
    @Column(name = "rev_tstmp", nullable = false)
    private long revTstmp;

    @Column(name = "actor_user_id", length = 255)
    private String actorUserId;

    @Column(name = "actor_role", length = 50)
    private String actorRole;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    public int getRev() {
        return rev;
    }

    public long getRevTstmp() {
        return revTstmp;
    }

    public Instant getRevisionInstant() {
        return Instant.ofEpochMilli(revTstmp);
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public void setActorRole(String actorRole) {
        this.actorRole = actorRole;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }
}
