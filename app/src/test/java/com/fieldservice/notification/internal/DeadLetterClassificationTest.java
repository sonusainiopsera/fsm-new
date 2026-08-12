package com.fieldservice.notification.internal;

import com.fieldservice.notification.api.TemplateRenderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterClassificationTest {

    @Test
    @DisplayName("TemplateRenderException is deterministic — dead-letter immediately")
    void templateRenderException_isDeterministic() {
        assertThat(DeadLetterService.isDeterministic(new TemplateRenderException("missing template")))
                .isTrue();
    }

    @Test
    @DisplayName("IllegalArgumentException (e.g. bad UUID) is deterministic")
    void illegalArgumentException_isDeterministic() {
        assertThat(DeadLetterService.isDeterministic(new IllegalArgumentException("bad uuid")))
                .isTrue();
    }

    @Test
    @DisplayName("NullPointerException (payload schema violation) is deterministic")
    void nullPointerException_isDeterministic() {
        assertThat(DeadLetterService.isDeterministic(new NullPointerException("null field")))
                .isTrue();
    }

    @Test
    @DisplayName("ClassCastException (payload type mismatch) is deterministic")
    void classCastException_isDeterministic() {
        assertThat(DeadLetterService.isDeterministic(new ClassCastException("int expected")))
                .isTrue();
    }

    @Test
    @DisplayName("DataAccessException (DB unavailable) is transient — re-queue")
    void dataAccessException_isTransient() {
        DataAccessException dae = new TransientDataAccessResourceException("connection pool exhausted");
        assertThat(DeadLetterService.isDeterministic(dae)).isFalse();
    }

    @Test
    @DisplayName("RuntimeException (generic infra fault) is transient — re-queue")
    void genericRuntimeException_isTransient() {
        assertThat(DeadLetterService.isDeterministic(new RuntimeException("timeout")))
                .isFalse();
    }

    @Test
    @DisplayName("IllegalStateException (application logic fault) is deterministic")
    void illegalStateException_isDeterministic() {
        assertThat(DeadLetterService.isDeterministic(new IllegalStateException("invariant violated")))
                .isTrue();
    }
}
