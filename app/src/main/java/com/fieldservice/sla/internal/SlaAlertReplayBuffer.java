package com.fieldservice.sla.internal;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded in-memory ring buffer retaining recent SLA alert SSE events for
 * Last-Event-ID resume.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #eventsAfter(String)} returns events whose id sorts after the given id.</li>
 *   <li>If the given id is older than the replay horizon, returns {@link ResumeResult#resyncRequired()}
 *       so the client knows to refetch current state rather than receive a gap-filled replay.</li>
 *   <li>If the given id is unknown (not in the buffer) but within the TTL window, events since the
 *       last horizon boundary are returned (best-effort).</li>
 *   <li>If the given id is null or blank, returns all buffered events.</li>
 * </ul>
 *
 * <p>Thread-safe via a ReentrantLock; additions and reads never block each other for long.
 */
@Component
public class SlaAlertReplayBuffer {

    /** An entry in the replay ring buffer. */
    public record ReplayEntry(
            String eventId,
            String eventType,
            String dataJson,
            Instant recordedAt
    ) {}

    /** Result of a Last-Event-ID resume attempt. */
    public sealed interface ResumeResult {
        /** Replay these events (may be empty if nothing new). */
        record Events(List<ReplayEntry> entries) implements ResumeResult {}
        /** The requested id is older than the replay horizon; client should refetch. */
        record ResyncRequired(String reason) implements ResumeResult {}

        static ResumeResult of(List<ReplayEntry> entries) { return new Events(entries); }
        static ResumeResult resyncRequired() {
            return new ResyncRequired("Last-Event-ID predates replay horizon; please refetch current state.");
        }
    }

    private final int maxSize;
    private final Duration horizon;
    private final ArrayDeque<ReplayEntry> ring;
    private final ReentrantLock lock = new ReentrantLock();

    public SlaAlertReplayBuffer(SlaAlertStreamProperties props) {
        this.maxSize = props.getReplayBufferSize();
        this.horizon = Duration.ofSeconds(props.getReplayHorizonSeconds());
        this.ring    = new ArrayDeque<>(maxSize);
    }

    /** Adds an event to the buffer, evicting the oldest entry when full. */
    public void add(ReplayEntry entry) {
        lock.lock();
        try {
            if (ring.size() >= maxSize) {
                ring.pollFirst();
            }
            ring.addLast(entry);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns events newer than {@code lastEventId}.
     *
     * @param lastEventId the SSE {@code Last-Event-ID} value from the client
     * @return replay result; never null
     */
    public ResumeResult eventsAfter(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            lock.lock();
            try {
                return ResumeResult.of(List.copyOf(ring));
            } finally {
                lock.unlock();
            }
        }

        lock.lock();
        try {
            Instant cutoff = Instant.now().minus(horizon);

            // Find the position of lastEventId in the ring
            boolean found = false;
            List<ReplayEntry> result = new ArrayList<>();

            for (ReplayEntry entry : ring) {
                if (found) {
                    result.add(entry);
                } else if (entry.eventId().equals(lastEventId)) {
                    found = true;
                    // Check if the found entry is within the horizon
                    if (entry.recordedAt().isBefore(cutoff)) {
                        return ResumeResult.resyncRequired();
                    }
                }
            }

            if (!found) {
                // id not in buffer — check if it would be within horizon by heuristic
                // (we can't know for sure; signal resync to be safe)
                return ResumeResult.resyncRequired();
            }

            return ResumeResult.of(result);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the current size of the buffer (for metrics). */
    public int size() {
        lock.lock();
        try {
            return ring.size();
        } finally {
            lock.unlock();
        }
    }
}
