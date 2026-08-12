package com.fieldservice.sla.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Bounded in-memory ring buffer for Last-Event-ID resume on SSE reconnect.
 *
 * <p>Stores up to {@code sla.stream.replay-buffer-capacity} recent alert events.
 * When the buffer is full the oldest entry is evicted. All operations are thread-safe.
 *
 * <p>Callers must treat a {@code null} return from {@link #replaySince(String)} as a
 * "resync required" signal: the requested event predates the buffer horizon and the
 * client must refetch current state via the REST API before reopening the stream.
 */
@Component
public class SlaAlertReplayBuffer {

    private static final Logger log = LoggerFactory.getLogger(SlaAlertReplayBuffer.class);

    private final int capacity;
    private final Deque<ReplayEntry> buffer;
    private final Set<String> knownIds;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public SlaAlertReplayBuffer(
            @Value("${sla.stream.replay-buffer-capacity:500}") int capacity) {
        this.capacity = capacity;
        this.buffer   = new ArrayDeque<>(capacity);
        this.knownIds = new HashSet<>(capacity * 2);
    }

    /**
     * Adds an event to the buffer. Duplicate event IDs are silently ignored.
     * Evicts the oldest entry when the buffer is at capacity.
     */
    public void add(String eventId, String eventType, String jsonData) {
        lock.writeLock().lock();
        try {
            if (knownIds.contains(eventId)) {
                return;
            }
            if (buffer.size() >= capacity) {
                ReplayEntry evicted = buffer.poll();
                if (evicted != null) {
                    knownIds.remove(evicted.eventId());
                }
            }
            buffer.offer(new ReplayEntry(eventId, eventType, jsonData));
            knownIds.add(eventId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns all events newer than {@code lastEventId}.
     *
     * @return ordered list of replay entries after the given id, empty if already current,
     *         or {@code null} if {@code lastEventId} is not in the buffer (resync required)
     */
    public List<ReplayEntry> replaySince(String lastEventId) {
        lock.readLock().lock();
        try {
            if (buffer.isEmpty()) {
                return List.of();
            }
            boolean found = false;
            List<ReplayEntry> result = new ArrayList<>();
            for (ReplayEntry entry : buffer) {
                if (found) {
                    result.add(entry);
                } else if (entry.eventId().equals(lastEventId)) {
                    found = true;
                }
            }
            if (!found) {
                log.debug("replay_buffer_miss lastEventId={} — resync required", lastEventId);
                return null; // caller must signal resync
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    int size() {
        lock.readLock().lock();
        try {
            return buffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public record ReplayEntry(String eventId, String eventType, String jsonData) {}
}
