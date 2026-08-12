package com.fieldservice.notification.api;

import java.util.Map;

/**
 * Public API for rendering versioned notification templates.
 *
 * <p>Implementations MUST enforce strict parameter substitution — no expression language,
 * no dynamic code evaluation. Any attempt to inject template-expression syntax, HTML markup,
 * or script content via a parameter value is rendered as inert escaped text.
 */
public interface TemplateRenderer {

    /**
     * Renders the subject and body for the named template on the given channel.
     *
     * @param templateKey   stable identifier registered in {@code notification_template}
     * @param channel       target delivery channel (determines encoding)
     * @param locale        BCP-47 locale string, e.g. {@code "en"}
     * @param params        typed substitution parameters; values are escaped per channel
     * @return rendered result containing subject (may be null for channels without one)
     *         and body
     * @throws TemplateRenderException if the template is not found, has no active version,
     *         contains an unrecognised placeholder, or a required parameter is missing
     */
    RenderedTemplate render(String templateKey, NotificationChannel channel,
                            String locale, Map<String, Object> params);

    /** Immutable result of a successful render. */
    record RenderedTemplate(String subject, String body) {}
}
