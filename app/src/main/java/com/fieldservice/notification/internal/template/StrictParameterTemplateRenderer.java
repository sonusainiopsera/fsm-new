package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.TemplateRenderException;
import com.fieldservice.notification.api.TemplateRenderer;
import com.fieldservice.notification.internal.NotificationTemplateEntity;
import com.fieldservice.notification.internal.NotificationTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parameter-substitution template renderer.
 *
 * <p>Only named placeholders of the form {@code {paramName}} (alphanumeric + underscore,
 * no spaces, no dots) are recognised. No expression language is supported — this is
 * purely a string replacement. Parameter values are channel-encoded before substitution
 * so user-supplied text cannot inject markup or template syntax.
 *
 * <p>Unknown placeholders in the template fail loudly ({@link TemplateRenderException})
 * so template authors discover mistakes immediately.
 */
@Service
class StrictParameterTemplateRenderer implements TemplateRenderer {

    /** Matches {identifier} — alphanumeric and underscore only. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private final NotificationTemplateRepository templateRepository;

    StrictParameterTemplateRenderer(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RenderedTemplate render(String templateKey, NotificationChannel channel,
                                   String locale, Map<String, String> params) {
        NotificationTemplateEntity tmpl = templateRepository
                .findByTemplateKeyAndChannelAndLocaleAndActiveTrue(templateKey, channel, locale)
                .orElseThrow(() -> new TemplateRenderException(
                        "No active template for key=" + templateKey + " channel=" + channel
                        + " locale=" + locale));

        String subject = tmpl.getSubjectTemplate() != null
                ? substitute(tmpl.getSubjectTemplate(), channel, params, templateKey)
                : null;
        String body = substitute(tmpl.getBodyTemplate(), channel, params, templateKey);

        return new RenderedTemplate(subject, body);
    }

    /**
     * Substitutes all {@code {placeholder}} occurrences in {@code template}.
     * Parameter values are channel-encoded before insertion.
     * Throws if any placeholder has no corresponding entry in {@code params}.
     */
    static String substitute(String template, NotificationChannel channel,
                              Map<String, String> params, String templateKey) {
        Matcher m = PLACEHOLDER.matcher(template);
        List<String> unknowns = new ArrayList<>();

        // Validate all placeholders are provided
        while (m.find()) {
            String name = m.group(1);
            if (!params.containsKey(name)) {
                unknowns.add(name);
            }
        }
        if (!unknowns.isEmpty()) {
            throw new TemplateRenderException(
                    "Template '" + templateKey + "' has unknown placeholders " + unknowns
                    + " — parameters supplied: " + params.keySet());
        }

        // Perform substitution with encoded values
        StringBuilder result = new StringBuilder(template.length() + 64);
        m.reset();
        int last = 0;
        while (m.find()) {
            result.append(template, last, m.start());
            String encoded = ChannelEncoders.encode(params.get(m.group(1)), channel);
            result.append(encoded);
            last = m.end();
        }
        result.append(template, last, template.length());
        return result.toString();
    }
}
