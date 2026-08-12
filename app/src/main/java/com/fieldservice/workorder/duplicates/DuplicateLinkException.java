package com.fieldservice.workorder.duplicates;

/**
 * Thrown when a duplicate-of link request cannot be fulfilled.
 *
 * <p>Carries a machine-readable {@code code} for the API error envelope.
 */
public class DuplicateLinkException extends RuntimeException {

    private final String code;

    public DuplicateLinkException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }

    public static DuplicateLinkException targetNotOpen(String targetRef) {
        return new DuplicateLinkException("DUPLICATE_TARGET_NOT_OPEN",
                "Target work order " + targetRef + " is not in an open state.");
    }

    public static DuplicateLinkException selfLink() {
        return new DuplicateLinkException("DUPLICATE_SELF_LINK",
                "A work order cannot be linked as a duplicate of itself.");
    }

    public static DuplicateLinkException alreadyLinked(String sourceRef) {
        return new DuplicateLinkException("DUPLICATE_ALREADY_LINKED",
                "Work order " + sourceRef + " is already linked as a duplicate.");
    }

    public static DuplicateLinkException cycle() {
        return new DuplicateLinkException("DUPLICATE_LINK_CYCLE",
                "Linking these work orders would create a cycle in the duplicate chain.");
    }
}
