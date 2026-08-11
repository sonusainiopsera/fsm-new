package com.fieldservice.app.outbox;

import com.fieldservice.app.fixtures.OutboxEventFixtures;
import com.fieldservice.platform.outbox.Confidential;
import com.fieldservice.platform.outbox.PiiRedaction;
import com.fieldservice.platform.outbox.Restricted;
import com.fieldservice.platform.outbox.RestrictedFieldException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for outbox infrastructure components (no Spring, no database).
 *
 * <p>Covers:
 * <ul>
 *   <li>PII redaction: {@code @Restricted} non-null → throws</li>
 *   <li>PII redaction: {@code @Confidential} String → masked to "***"</li>
 *   <li>PII redaction: {@code @Confidential} numeric → set to null</li>
 *   <li>PII redaction: clean payload passes through unmodified</li>
 *   <li>PiiRedaction.hasRestrictedFields utility</li>
 * </ul>
 */
class OutboxUnitTest {

    // ---- @Restricted rejection ------------------------------------------------

    @Test
    @DisplayName("sanitize throws RestrictedFieldException when @Restricted field is non-null")
    void restricted_non_null_throws() {
        var payload = new OutboxEventFixtures.PayloadWithRestrictedField(
                "user-001", "$2a$12$hashvalue");

        assertThatThrownBy(() -> PiiRedaction.sanitize(payload))
                .isInstanceOf(RestrictedFieldException.class)
                .hasMessageContaining("passwordHash");
    }

    @Test
    @DisplayName("sanitize passes when @Restricted field is null")
    void restricted_null_passes() {
        var payload = new OutboxEventFixtures.PayloadWithRestrictedField("user-002", null);
        PiiRedaction.sanitize(payload); // must not throw
        assertThat(payload.userId).isEqualTo("user-002");
    }

    // ---- @Confidential masking ------------------------------------------------

    @Test
    @DisplayName("sanitize masks @Confidential String fields with ***")
    void confidential_string_masked() {
        var payload = new OutboxEventFixtures.PayloadWithConfidentialFields(
                "WO-001", "alice@example.com", "+1-555-0100", 51.5074, -0.1278);

        PiiRedaction.sanitize(payload);

        assertThat(payload.contactEmail).isEqualTo("***");
        assertThat(payload.contactPhone).isEqualTo("***");
        assertThat(payload.workOrderRef).isEqualTo("WO-001");
    }

    @Test
    @DisplayName("sanitize nulls @Confidential numeric (GPS) fields")
    void confidential_numeric_nulled() {
        var payload = new OutboxEventFixtures.PayloadWithConfidentialFields(
                "WO-002", "bob@example.com", "+1-555-0200", 48.8566, 2.3522);

        PiiRedaction.sanitize(payload);

        assertThat(payload.gpsLatitude).isNull();
        assertThat(payload.gpsLongitude).isNull();
    }

    @Test
    @DisplayName("sanitize leaves non-PII fields unmodified")
    void non_pii_fields_untouched() {
        var payload = new OutboxEventFixtures.WorkOrderAssignedPayload(
                "WO-CLEAN-001", "HIGH", java.util.UUID.randomUUID());

        PiiRedaction.sanitize(payload);

        assertThat(payload.reference).isEqualTo("WO-CLEAN-001");
        assertThat(payload.priority).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("sanitize is a no-op on null payload")
    void null_payload_is_noop() {
        PiiRedaction.sanitize(null); // must not throw
    }

    // ---- hasRestrictedFields utility ------------------------------------------

    @Test
    @DisplayName("hasRestrictedFields returns true for payload with @Restricted field")
    void has_restricted_fields_true() {
        assertThat(PiiRedaction.hasRestrictedFields(
                OutboxEventFixtures.PayloadWithRestrictedField.class)).isTrue();
    }

    @Test
    @DisplayName("hasRestrictedFields returns false for clean payload")
    void has_restricted_fields_false() {
        assertThat(PiiRedaction.hasRestrictedFields(
                OutboxEventFixtures.WorkOrderAssignedPayload.class)).isFalse();
    }

    // ---- Inner-class annotation test (verifies reflective walk) ---------------

    static class ParentPayload {
        @Restricted
        String token;
        String name;
        ParentPayload(String token, String name) { this.token = token; this.name = name; }
    }

    static class ChildPayload extends ParentPayload {
        @Confidential
        String email;
        ChildPayload(String token, String name, String email) {
            super(token, name);
            this.email = email;
        }
    }

    @Test
    @DisplayName("sanitize traverses inherited fields from parent classes")
    void traverses_parent_fields() {
        var payload = new ChildPayload("secret-token", "Alice", "alice@example.com");

        assertThatThrownBy(() -> PiiRedaction.sanitize(payload))
                .isInstanceOf(RestrictedFieldException.class)
                .hasMessageContaining("token");
    }

    @Test
    @DisplayName("sanitize masks inherited @Confidential fields when @Restricted is null")
    void masks_inherited_confidential() {
        var payload = new ChildPayload(null, "Bob", "bob@example.com");

        PiiRedaction.sanitize(payload);

        assertThat(payload.email).isEqualTo("***");
        assertThat(payload.name).isEqualTo("Bob");
    }
}
