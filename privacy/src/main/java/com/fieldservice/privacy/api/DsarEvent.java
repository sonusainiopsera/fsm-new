package com.fieldservice.privacy.api;

/** Events that drive DSAR request lifecycle transitions. */
public enum DsarEvent {
    /** Begin identity verification workflow. RECEIVED → IDENTITY_PENDING */
    BEGIN_VERIFICATION,
    /** Record successful identity verification. IDENTITY_PENDING → VERIFIED */
    RECORD_VERIFICATION,
    /** Mark request as directly verified (skip pending step). RECEIVED → VERIFIED */
    VERIFY_DIRECT,
    /** Worker job claims the request for assembly. VERIFIED → IN_PROGRESS */
    CLAIM,
    /** Worker job marks the export complete. IN_PROGRESS → FULFILLED */
    FULFIL,
    /** Handler rejects the request. Any non-terminal → REJECTED */
    REJECT,
    /** Subject or handler withdraws the request. Any non-terminal → WITHDRAWN */
    WITHDRAW
}
