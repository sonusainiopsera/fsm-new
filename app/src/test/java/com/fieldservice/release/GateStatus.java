package com.fieldservice.release;

/**
 * Result classification for a single invariant gate.
 *
 * <p>Exit-code mapping:
 * <ul>
 *   <li>PASS — invariant holds.</li>
 *   <li>FAIL — invariant violated; triggers non-zero exit and rollback.</li>
 *   <li>SKIP — gate not executed (dry-run suppresses write gates).</li>
 *   <li>ERROR — unexpected exception; treated as FAIL for exit-code purposes.</li>
 * </ul>
 */
public enum GateStatus {
    PASS,
    FAIL,
    SKIP,
    ERROR
}
