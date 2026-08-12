package com.fieldservice.photo.application;

/** Thrown when a declared content type is not on the photo upload allow-list. */
public class DisallowedContentTypeException extends RuntimeException {

    private final String submittedType;

    public DisallowedContentTypeException(String submittedType) {
        super("Content type not allowed: " + submittedType
                + ". Allowed types: image/jpeg, image/png, image/webp");
        this.submittedType = submittedType;
    }

    public String getSubmittedType() { return submittedType; }
}
