package com.fieldservice.notification.internal.template;

import com.fieldservice.notification.api.NotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelEncodersTest {

    // ── EMAIL ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EMAIL: ampersand is escaped to &amp;")
    void email_escapesAmpersand() {
        assertThat(ChannelEncoders.encode("A & B", NotificationChannel.EMAIL))
                .isEqualTo("A &amp; B");
    }

    @Test
    @DisplayName("EMAIL: angle brackets are escaped")
    void email_escapesAngleBrackets() {
        String result = ChannelEncoders.encode("<script>alert('xss')</script>", NotificationChannel.EMAIL);
        assertThat(result).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("EMAIL: double quotes are escaped")
    void email_escapesDoubleQuotes() {
        assertThat(ChannelEncoders.encode("say \"hi\"", NotificationChannel.EMAIL))
                .isEqualTo("say &quot;hi&quot;");
    }

    @Test
    @DisplayName("EMAIL: apostrophe is escaped to &#x27;")
    void email_escapesApostrophe() {
        assertThat(ChannelEncoders.encode("it's", NotificationChannel.EMAIL))
                .isEqualTo("it&#x27;s");
    }

    @ParameterizedTest(name = "EMAIL injection attempt: {0}")
    @CsvSource({
            "<img src=x onerror=alert(1)>, must not contain <img",
            "javascript:alert(1), javascript:alert(1)",
            "<a href=\"evil\">click</a>, must not contain <a"
    })
    @DisplayName("EMAIL: HTML injection attempts are rendered inert")
    void email_htmlInjectionRenderedInert(String input, String expectation) {
        String result = ChannelEncoders.encode(input, NotificationChannel.EMAIL);
        assertThat(result).doesNotContain("<");
        assertThat(result).doesNotContain(">");
    }

    @Test
    @DisplayName("EMAIL: null input returns empty string")
    void email_nullReturnsEmpty() {
        assertThat(ChannelEncoders.encode(null, NotificationChannel.EMAIL)).isEmpty();
    }

    // ── SMS ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SMS: HTML tags are stripped")
    void sms_stripsHtmlTags() {
        String result = ChannelEncoders.encode("<b>Important</b>", NotificationChannel.SMS);
        assertThat(result).isEqualTo("Important");
    }

    @Test
    @DisplayName("SMS: HTML entities are decoded to plain text")
    void sms_decodesEntities() {
        assertThat(ChannelEncoders.encode("A &amp; B", NotificationChannel.SMS)).isEqualTo("A & B");
    }

    @Test
    @DisplayName("SMS: extra whitespace is normalised")
    void sms_normalisesWhitespace() {
        assertThat(ChannelEncoders.encode("  a  b  ", NotificationChannel.SMS)).isEqualTo("a b");
    }

    @Test
    @DisplayName("SMS: no raw HTML passthrough")
    void sms_noRawHtmlPassthrough() {
        String result = ChannelEncoders.encode("<script>evil</script>", NotificationChannel.SMS);
        assertThat(result).doesNotContain("<").doesNotContain(">").isEqualTo("evil");
    }

    // ── PUSH ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUSH: behaves identically to SMS (plain text)")
    void push_stripsHtml() {
        String result = ChannelEncoders.encode("<p>Hello <em>world</em></p>", NotificationChannel.PUSH);
        assertThat(result).isEqualTo("Hello world");
    }

    // ── IN_APP ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IN_APP: value passed through unmodified (client escapes)")
    void inApp_passThrough() {
        String raw = "<b>Hello & World</b>";
        assertThat(ChannelEncoders.encode(raw, NotificationChannel.IN_APP)).isEqualTo(raw);
    }

    @Test
    @DisplayName("IN_APP: null returns empty string")
    void inApp_nullReturnsEmpty() {
        assertThat(ChannelEncoders.encode(null, NotificationChannel.IN_APP)).isEmpty();
    }
}
