package com.fieldservice.notification.api;

/** Thrown when template rendering fails due to missing template, unknown placeholder, or missing parameter. */
public class TemplateRenderException extends RuntimeException {

    public TemplateRenderException(String message) {
        super(message);
    }

    public TemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
