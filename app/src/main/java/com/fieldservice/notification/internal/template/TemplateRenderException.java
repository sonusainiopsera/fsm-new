package com.fieldservice.notification.internal.template;

/**
 * Thrown when template rendering cannot complete deterministically.
 *
 * <p>A {@code TemplateRenderException} signals a <em>deterministic</em> failure — the same
 * payload will always fail — and therefore routes directly to dead-letter rather than retry.
 */
public class TemplateRenderException extends RuntimeException {

    public TemplateRenderException(String message) {
        super(message);
    }

    public TemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
