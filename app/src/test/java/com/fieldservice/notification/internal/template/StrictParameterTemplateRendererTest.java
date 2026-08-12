package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import com.fieldservice.notification.api.TemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for StrictParameterTemplateRenderer (WO-196, AC-2).
 *
 * <p>These tests run with no Spring context — the repository is a Mockito stub.
 */
class StrictParameterTemplateRendererTest {

    private NotificationTemplateRepository repository;
    private StrictParameterTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(NotificationTemplateRepository.class);
        renderer   = new StrictParameterTemplateRenderer(repository);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private NotificationTemplateEntity makeTemplate(String body, String subject) {
        NotificationTemplateEntity t = Mockito.spy(new NotificationTemplateEntity());
        Mockito.doReturn(body).when(t).getBodyTemplate();
        Mockito.doReturn(subject).when(t).getSubjectTemplate();
        return t;
    }

    private void stubTemplate(String key, NotificationChannel channel, String body, String subject) {
        NotificationTemplateEntity t = makeTemplate(body, subject);
        when(repository.findByTemplateKeyAndChannelAndLocaleAndActiveTrue(eq(key), eq(channel), any()))
                .thenReturn(Optional.of(t));
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void renders_named_placeholders_correctly() {
        stubTemplate("test.key", NotificationChannel.IN_APP,
                "Hello {{name}}, your order {{ref}} is ready.", null);

        TemplateRenderer.RenderedTemplate result = renderer.render(
                "test.key", NotificationChannel.IN_APP, "en",
                Map.of("name", "Alice", "ref", "WO-001"));

        assertThat(result.body()).isEqualTo("Hello Alice, your order WO-001 is ready.");
    }

    @Test
    void null_subject_template_returns_null_subject() {
        stubTemplate("test.key", NotificationChannel.IN_APP, "Body text {{x}}.", null);

        TemplateRenderer.RenderedTemplate result = renderer.render(
                "test.key", NotificationChannel.IN_APP, "en", Map.of("x", "value"));

        assertThat(result.subject()).isNull();
        assertThat(result.body()).isEqualTo("Body text value.");
    }

    @Test
    void renders_subject_and_body() {
        stubTemplate("email.key", NotificationChannel.EMAIL,
                "Dear {{name}}, your case is {{status}}.",
                "Case update: {{ref}}");

        TemplateRenderer.RenderedTemplate result = renderer.render(
                "email.key", NotificationChannel.EMAIL, "en",
                Map.of("name", "Bob", "status", "open", "ref", "WO-002"));

        assertThat(result.subject()).isEqualTo("Case update: WO-002");
        assertThat(result.body()).isEqualTo("Dear Bob, your case is open.");
    }

    // ── Injection resistance (AC-2) ───────────────────────────────────────────

    @Test
    void html_in_parameter_value_is_escaped_for_email() {
        stubTemplate("email.key", NotificationChannel.EMAIL,
                "Fault: {{faultDesc}}.", "Subject {{ref}}");

        TemplateRenderer.RenderedTemplate result = renderer.render(
                "email.key", NotificationChannel.EMAIL, "en",
                Map.of("faultDesc", "<script>alert(1)</script>", "ref", "X"));

        assertThat(result.body()).contains("&lt;script&gt;");
        assertThat(result.body()).doesNotContain("<script>");
    }

    @Test
    void template_expression_syntax_in_parameter_is_rendered_inert() {
        // A parameter value containing {{...}} must NOT be recursively evaluated
        stubTemplate("test.key", NotificationChannel.IN_APP,
                "Note: {{userInput}}.", null);

        TemplateRenderer.RenderedTemplate result = renderer.render(
                "test.key", NotificationChannel.IN_APP, "en",
                Map.of("userInput", "{{technicianName}}"));

        // The inner {{technicianName}} is treated as literal text, not a placeholder
        assertThat(result.body()).isEqualTo("Note: {{technicianName}}.");
    }

    @Test
    void script_in_parameter_is_inert_for_in_app_channel() {
        stubTemplate("test.key", NotificationChannel.IN_APP,
                "Message: {{msg}}.", null);

        TemplateRenderer.RenderedTemplate result = renderer.render(
                "test.key", NotificationChannel.IN_APP, "en",
                Map.of("msg", "<script>evil()</script>"));

        // IN_APP sanitises control chars but does not HTML-encode — client escapes on render
        assertThat(result.body()).doesNotContain("&lt;");
        // The template engine does not evaluate or execute the content
        assertThat(result.body()).contains("<script>evil()</script>");
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void throws_when_template_not_found() {
        when(repository.findByTemplateKeyAndChannelAndLocaleAndActiveTrue(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                renderer.render("missing.key", NotificationChannel.IN_APP, "en", Map.of()))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("No active template");
    }

    @Test
    void throws_on_unknown_placeholder_in_body() {
        stubTemplate("test.key", NotificationChannel.IN_APP,
                "Hello {{name}} and {{unknown}}.", null);

        assertThatThrownBy(() ->
                renderer.render("test.key", NotificationChannel.IN_APP, "en",
                        Map.of("name", "Alice")))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void throws_on_unknown_placeholder_in_subject() {
        stubTemplate("test.key", NotificationChannel.IN_APP,
                "Body {{x}}.", "Subject {{missing}}");

        assertThatThrownBy(() ->
                renderer.render("test.key", NotificationChannel.IN_APP, "en",
                        Map.of("x", "val")))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void unknown_placeholder_does_not_emit_blank() {
        // Verify the renderer fails loudly rather than emitting empty text
        stubTemplate("test.key", NotificationChannel.IN_APP,
                "Value: {{missing}}.", null);

        assertThatThrownBy(() ->
                renderer.render("test.key", NotificationChannel.IN_APP, "en", Map.of()))
                .isInstanceOf(TemplateRenderException.class);
    }
}
