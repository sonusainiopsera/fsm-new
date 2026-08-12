package com.fieldservice.dispatch.api;

import java.util.UUID;

/** An excluded technician with the machine-readable reason for exclusion. */
public record ExcludedCandidate(UUID technicianId, ExclusionReason reason) {}
