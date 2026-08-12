package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.TemplateRenderException;
import com.fieldservice.notification.api.TemplateRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StrictParameterTemplateRenderer} — no Spring context required.
 * Tests focus on injection-safety invariants and strict-substitution contract.
 */
class StrictParameterTemplateRendererTest {

    private static final String TEMPLATE_KEY = "test_template";

    // ── Substitution correctness ──────────────────────────────────────────────

    @Test
    @DisplayName("substitutes a single placeholder with the provided param value")
    void singlePlaceholder_substituted() {
        String result = StrictParameterTemplateRenderer.substitute(
                "Hello {name}!", NotificationChannel.IN_APP,
                Map.of("name", "Alice"), TEMPLATE_KEY);
        assertThat(result).isEqualTo("Hello Alice!");
    }

    @Test
    @DisplayName("substitutes multiple distinct placeholders")
    void multiplePlaceholders_allSubstituted() {
        String result = StrictParameterTemplateRenderer.substitute(
                "{greeting} {name}, ref {ref}",
                NotificationChannel.IN_APP,
                Map.of("greeting", "Hi", "name", "Bob", "ref", "WO-001"),
                TEMPLATE_KEY);
        assertThat(result).isEqualTo("Hi Bob, ref WO-001");
    }

    @Test
    @DisplayName("same placeholder repeated twice is substituted both times")
    void repeatedPlaceholder_substitutedBoth() {
        String result = StrictParameterTemplateRenderer.substitute(
                "{ref} and {ref}", NotificationChannel.IN_APP,
                Map.of("ref", "X"), TEMPLATE_KEY);
        assertThat(result).isEqualTo("X and X");
    }

    @Test
    @DisplayName("template with no placeholders returns the template unchanged")
    void noPlaceholders_returnsOriginal() {
        String result = StrictParameterTemplateRenderer.substitute(
                "No placeholders here", NotificationChannel.IN_APP,
                Map.of(), TEMPLATE_KEY);
        assertThat(result).isEqualTo("No placeholders here");
    }

    // ── Unknown placeholder rejection ─────────────────────────────────────────

    @Test
    @DisplayName("unknown placeholder in template throws TemplateRenderException")
    void unknownPlaceholder_throwsException() {
        assertThatThrownBy(() ->
                StrictParameterTemplateRenderer.substitute(
                        "Hello {unknown}!", NotificationChannel.IN_APP,
                        Map.of(), TEMPLATE_KEY))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("partially provided params throw exception for missing ones")
    void partialParams_throwsForMissing() {
        assertThatThrownBy(() ->
                StrictParameterTemplateRenderer.substitute(
                        "Hello {name}, ref {ref}",
                        NotificationChannel.IN_APP,
                        Map.of("name", "Alice"), // missing 'ref'
                        TEMPLATE_KEY))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("ref");
    }

    // ── Injection-safety: template-expression syntax is NOT evaluated ─────────

    @ParameterizedTest(name = "injection attempt: {0}")
    @ValueSource(strings = {
            "${7*7}",
            "#{someBean.method()}",
            "{{nested}}",
            "{% for x in list %}",
            "<%= Math.random() %>"
    })
    @DisplayName("template-expression syntax in param value is rendered inert (not evaluated)")
    void templateExpressionSyntax_renderedInert(String injectionAttempt) {
        String result = StrictParameterTemplateRenderer.substitute(
                "Value: {val}", NotificationChannel.IN_APP,
                Map.of("val", injectionAttempt), TEMPLATE_KEY);
        // The raw string is present in output — it is NOT evaluated
        assertThat(result).contains(injectionAttempt);
        // No numeric result (e.g. "49" from 7*7) appears
        assertThat(result).doesNotContain("49");
    }

    @ParameterizedTest(name = "HTML injection: {0}")
    @ValueSource(strings = {
            "<script>alert('xss')</script>",
            "<img src=x onerror=alert(1)>",
            "<a href=\"javascript:evil\">click</a>"
    })
    @DisplayName("HTML injection in param value is escaped for EMAIL channel")
    void htmlInjection_escapedForEmail(String htmlPayload) {
        String result = StrictParameterTemplateRenderer.substitute(
                "Body: {content}", NotificationChannel.EMAIL,
                Map.of("content", htmlPayload), TEMPLATE_KEY);
        assertThat(result).doesNotContain("<script>");
        assertThat(result).doesNotContain("<img");
        assertThat(result).doesNotContain("onerror");
    }

    @Test
    @DisplayName("HTML markup in param value is passed through unescaped for IN_APP channel")
    void htmlMarkup_passedThroughForInApp() {
        String result = StrictParameterTemplateRenderer.substitute(
                "Value: {val}", NotificationChannel.IN_APP,
                Map.of("val", "<b>bold</b>"), TEMPLATE_KEY);
        assertThat(result).contains("<b>bold</b>");
    }

    @Test
    @DisplayName("param value containing curly braces is substituted as literal text")
    void curlyBracesInParamValue_literalText() {
        String result = StrictParameterTemplateRenderer.substitute(
                "Template: {tmpl}", NotificationChannel.IN_APP,
                Map.of("tmpl", "{fake placeholder}"), TEMPLATE_KEY);
        // The substituted value contains literal braces — they are NOT re-evaluated
        assertThat(result).isEqualTo("Template: {fake placeholder}");
    }

    // ── Rendered output structure ─────────────────────────────────────────────

    @Test
    @DisplayName("RenderedTemplate record carries subject and body")
    void renderedTemplate_subjectAndBody() {
        TemplateRenderer.RenderedTemplate rt = new TemplateRenderer.RenderedTemplate(
                "Subject text", "Body text");
        assertThat(rt.subject()).isEqualTo("Subject text");
        assertThat(rt.body()).isEqualTo("Body text");
    }

    @Test
    @DisplayName("null subject is allowed (channels without a subject)")
    void renderedTemplate_nullSubjectAllowed() {
        TemplateRenderer.RenderedTemplate rt = new TemplateRenderer.RenderedTemplate(null, "Body");
        assertThat(rt.subject()).isNull();
        assertThat(rt.body()).isEqualTo("Body");
    }
}
