package com.fieldservice.dispatch.api;

import java.util.UUID;

/** A technician removed from the dispatch candidate pool with an auditable reason code. */
public record ExcludedCandidate(UUID technicianId, ExclusionReason reason) {}
