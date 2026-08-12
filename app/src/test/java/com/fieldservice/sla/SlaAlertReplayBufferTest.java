package com.fieldservice.sla;

import com.fieldservice.sla.internal.SlaAlertReplayBuffer;
import com.fieldservice.sla.internal.SlaAlertStreamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlaAlertReplayBufferTest {

    private SlaAlertReplayBuffer buffer;

    @BeforeEach
    void setUp() {
        SlaAlertStreamProperties props = new SlaAlertStreamProperties();
        props.setReplayBufferSize(10);
        props.setReplayHorizonSeconds(300);
        buffer = new SlaAlertReplayBuffer(props);
    }

    @Test
    void eventsAfter_nullLastEventId_returnsAllBuffered() {
        buffer.add(entry("e1"));
        buffer.add(entry("e2"));

        SlaAlertReplayBuffer.ResumeResult result = buffer.eventsAfter(null);

        assertThat(result).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.Events.class);
        assertThat(((SlaAlertReplayBuffer.ResumeResult.Events) result).entries())
                .extracting(SlaAlertReplayBuffer.ReplayEntry::eventId)
                .containsExactly("e1", "e2");
    }

    @Test
    void eventsAfter_blankLastEventId_returnsAllBuffered() {
        buffer.add(entry("e1"));

        SlaAlertReplayBuffer.ResumeResult result = buffer.eventsAfter("  ");

        assertThat(result).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.Events.class);
        assertThat(((SlaAlertReplayBuffer.ResumeResult.Events) result).entries()).hasSize(1);
    }

    @Test
    void eventsAfter_knownId_returnsSubsequentEventsOnly() {
        buffer.add(entry("e1"));
        buffer.add(entry("e2"));
        buffer.add(entry("e3"));

        SlaAlertReplayBuffer.ResumeResult result = buffer.eventsAfter("e1");

        assertThat(result).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.Events.class);
        assertThat(((SlaAlertReplayBuffer.ResumeResult.Events) result).entries())
                .extracting(SlaAlertReplayBuffer.ReplayEntry::eventId)
                .containsExactly("e2", "e3");
    }

    @Test
    void eventsAfter_mostRecentId_returnsEmpty() {
        buffer.add(entry("e1"));
        buffer.add(entry("e2"));

        SlaAlertReplayBuffer.ResumeResult result = buffer.eventsAfter("e2");

        assertThat(result).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.Events.class);
        assertThat(((SlaAlertReplayBuffer.ResumeResult.Events) result).entries()).isEmpty();
    }

    @Test
    void eventsAfter_unknownId_returnsResyncRequired() {
        buffer.add(entry("e1"));

        SlaAlertReplayBuffer.ResumeResult result = buffer.eventsAfter("does-not-exist");

        assertThat(result).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.ResyncRequired.class);
    }

    @Test
    void eventsAfter_idOutsideHorizon_returnsResyncRequired() {
        SlaAlertStreamProperties props = new SlaAlertStreamProperties();
        props.setReplayBufferSize(10);
        props.setReplayHorizonSeconds(5);
        SlaAlertReplayBuffer shortBuffer = new SlaAlertReplayBuffer(props);

        SlaAlertReplayBuffer.ReplayEntry stale = new SlaAlertReplayBuffer.ReplayEntry(
                "stale", "SLA_AT_RISK", "{}", Instant.now().minusSeconds(60));
        shortBuffer.add(stale);
        shortBuffer.add(entry("recent"));

        SlaAlertReplayBuffer.ResumeResult result = shortBuffer.eventsAfter("stale");

        assertThat(result).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.ResyncRequired.class);
    }

    @Test
    void add_whenFull_evictsOldestEntry() {
        SlaAlertStreamProperties props = new SlaAlertStreamProperties();
        props.setReplayBufferSize(3);
        props.setReplayHorizonSeconds(300);
        SlaAlertReplayBuffer small = new SlaAlertReplayBuffer(props);

        small.add(entry("e1"));
        small.add(entry("e2"));
        small.add(entry("e3"));
        small.add(entry("e4")); // evicts e1

        assertThat(small.size()).isEqualTo(3);
        // e1 was evicted — requesting from it should return resync
        assertThat(small.eventsAfter("e1"))
                .isInstanceOf(SlaAlertReplayBuffer.ResumeResult.ResyncRequired.class);
        // e2 is still there
        SlaAlertReplayBuffer.ResumeResult fromE2 = small.eventsAfter("e2");
        assertThat(fromE2).isInstanceOf(SlaAlertReplayBuffer.ResumeResult.Events.class);
        assertThat(((SlaAlertReplayBuffer.ResumeResult.Events) fromE2).entries())
                .extracting(SlaAlertReplayBuffer.ReplayEntry::eventId)
                .containsExactly("e3", "e4");
    }

    @Test
    void size_reflectsCurrentBufferLength() {
        assertThat(buffer.size()).isZero();
        buffer.add(entry("e1"));
        assertThat(buffer.size()).isEqualTo(1);
        buffer.add(entry("e2"));
        assertThat(buffer.size()).isEqualTo(2);
    }

    private static SlaAlertReplayBuffer.ReplayEntry entry(String id) {
        return new SlaAlertReplayBuffer.ReplayEntry(id, "SLA_AT_RISK", "{\"id\":\"" + id + "\"}", Instant.now());
    }
}
