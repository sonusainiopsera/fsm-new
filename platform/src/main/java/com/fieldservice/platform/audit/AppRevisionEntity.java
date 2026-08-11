package com.fieldservice.platform.audit;

import jakarta.persistence.*;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.io.Serializable;

/**
 * Custom Envers revision entity backed by the Flyway-created {@code revinfo} table.
 * Extends the default (id, timestamp) pair with actor attribution fields populated by
 * {@link AppRevisionListener} from the Spring Security context and MDC.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(AppRevisionListener.class)
public class AppRevisionEntity implements Serializable {

    @Id
    @SequenceGenerator(name = "revinfo_seq", sequenceName = "revinfo_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revinfo_seq")
    @RevisionNumber
    private long rev;

    @RevisionTimestamp
    @Column(name = "revtstmp", nullable = false)
    private long revtstmp;

    @Column(name = "actor_user_id", length = 255)
    private String actorUserId;

    @Column(name = "actor_role", length = 100)
    private String actorRole;

    @Column(name = "trace_id", length = 36)
    private String traceId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    public long getRev() { return rev; }
    public long getRevtstmp() { return revtstmp; }
    public String getActorUserId() { return actorUserId; }
    public String getActorRole() { return actorRole; }
    public String getTraceId() { return traceId; }
    public String getClientIp() { return clientIp; }

    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
}
