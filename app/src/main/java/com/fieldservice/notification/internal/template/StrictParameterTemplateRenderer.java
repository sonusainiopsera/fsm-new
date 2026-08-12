package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.TemplateRenderer;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parameter-substitution template renderer (WO-196).
 *
 * <p><strong>Security invariants enforced here:</strong>
 * <ol>
 *   <li>Placeholders use {@code {{paramName}}} syntax only. No expression language,
 *       no OGNL, no Spring EL, no Freemarker, no Thymeleaf — the substitution engine
 *       is a regex replace that copies characters and fills named slots.</li>
 *   <li>Unknown placeholders (present in template, absent from params map) fail loudly
 *       with a {@link TemplateRenderException} rather than emitting an empty string that
 *       could mislead a reader.</li>
 *   <li>Every substituted value is passed through {@link ChannelEncoders#encode} before
 *       insertion, so HTML markup, script tags, and template-expression syntax in a
 *       parameter value are rendered as inert escaped text.</li>
 *   <li>No user-supplied input may reach the {@code templateKey}, {@code channel}, or
 *       {@code locale} parameters — those are always hard-coded by the consumer.</li>
 * </ol>
 */
@Service
class StrictParameterTemplateRenderer implements TemplateRenderer {

    /** Matches {@code {{identifier}}} — only word characters are valid placeholder names. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private final NotificationTemplateRepository templateRepository;

    StrictParameterTemplateRenderer(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Override
    public RenderedTemplate render(String templateKey, NotificationChannel channel,
                                   String locale, Map<String, Object> params) {
        NotificationTemplateEntity tmpl = templateRepository
                .findByTemplateKeyAndChannelAndLocaleAndActiveTrue(templateKey, channel, locale)
                .orElseThrow(() -> new TemplateRenderException(
                        "No active template for key=%s channel=%s locale=%s"
                                .formatted(templateKey, channel, locale)));

        String subject = tmpl.getSubjectTemplate() != null
                ? substitute(tmpl.getSubjectTemplate(), channel, params, templateKey)
                : null;
        String body = substitute(tmpl.getBodyTemplate(), channel, params, templateKey);

        return new RenderedTemplate(subject, body);
    }

    // ── Substitution engine ───────────────────────────────────────────────────

    private String substitute(String template, NotificationChannel channel,
                               Map<String, Object> params, String templateKey) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 64);
        while (m.find()) {
            String name = m.group(1);
            if (!params.containsKey(name)) {
                throw new TemplateRenderException(
                        "Template '%s' references unknown placeholder '{{%s}}' — " +
                        "either the template has an error or the consumer did not supply this parameter"
                                .formatted(templateKey, name));
            }
            Object value = params.get(name);
            String encoded = ChannelEncoders.encode(value, channel);
            m.appendReplacement(sb, Matcher.quoteReplacement(encoded));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
