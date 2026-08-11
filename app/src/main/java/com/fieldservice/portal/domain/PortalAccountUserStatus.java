package com.fieldservice.portal.domain;

/**
 * Lifecycle states for a portal account linkage row.
 *
 * <ul>
 *   <li>{@link #PENDING} — invitation issued but the user has not yet activated it.</li>
 *   <li>{@link #ACTIVE} — invitation consumed; user can authenticate and query portal data.</li>
 *   <li>{@link #SUSPENDED} — access revoked by an admin; treated identically to PENDING
 *       by the scope resolver (fails closed).</li>
 * </ul>
 */
public enum PortalAccountUserStatus {
    PENDING,
    ACTIVE,
    SUSPENDED
}
