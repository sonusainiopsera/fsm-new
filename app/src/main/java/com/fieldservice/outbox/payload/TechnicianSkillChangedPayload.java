package com.fieldservice.outbox.payload;

import java.util.UUID;

/** Outbox event payload for technician skill link create/update/delete events. No PII. */
public record TechnicianSkillChangedPayload(
        UUID technicianId,
        UUID skillId,
        String skillCode,
        String proficiency,
        String changeType
) {
    public static final String EVENT_TYPE     = "workforce.TechnicianSkillChanged";
    public static final String AGGREGATE_TYPE = "TechnicianSkill";
}
