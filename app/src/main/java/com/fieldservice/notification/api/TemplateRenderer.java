package com.fieldservice.notification.api;

import java.util.Map;

/**
 * Renders versioned notification templates with strict parameter substitution.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>Only named placeholders in the form {@code {paramName}} are substituted.</li>
 *   <li>No expression language or dynamic evaluation is ever performed.</li>
 *   <li>Unknown placeholders in the template cause a {@link TemplateRenderException}.</li>
 *   <li>Parameter values are encoded for the target channel before substitution.</li>
 * </ul>
 */
public interface TemplateRenderer {

    /**
     * Result of rendering a template — subject and body for the given channel.
     *
     * @param subject rendered subject (may be null for channels without a subject)
     * @param body    rendered body, channel-encoded
     */
    record RenderedTemplate(String subject, String body) {}

    /**
     * Renders the active template identified by {@code templateKey} for the given
     * {@code channel} and {@code locale}, substituting {@code params} for placeholders.
     *
     * @param templateKey unique template identifier (e.g. "assignment_notification")
     * @param channel     target delivery channel
     * @param locale      BCP-47 locale tag (e.g. "en")
     * @param params      parameter map; keys must match all placeholders in the template
     * @return the rendered subject and body
     * @throws TemplateRenderException if the template is missing, has no active version,
     *                                  an unknown placeholder is found, or a required
     *                                  parameter is absent
     */
    RenderedTemplate render(String templateKey, NotificationChannel channel,
                            String locale, Map<String, String> params);
}
