package com.fieldservice.workorder.domain;

/** Enum-bound priority levels for work orders. Matches the DB CHECK constraint vocabulary. */
public enum WorkOrderPriority {
    LOW, MEDIUM, HIGH, CRITICAL;

    /** Returns the string value stored in the database (same as name()). */
    public String toDbValue() { return name(); }
}
