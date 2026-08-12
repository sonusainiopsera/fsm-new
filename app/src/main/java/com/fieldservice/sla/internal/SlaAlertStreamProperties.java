package com.fieldservice.sla.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the SLA alert SSE stream subsystem.
 *
 * <p>Bound to {@code app.sla.alert-stream.*} properties.
 */
@ConfigurationProperties(prefix = "app.sla.alert-stream")
public class SlaAlertStreamProperties {

    /** Interval in seconds between heartbeat frames. Default 15 s. */
    private int heartbeatIntervalSeconds = 15;

    /** Maximum number of events retained in the replay buffer. */
    private int replayBufferSize = 500;

    /** Seconds of replay horizon. Events older than this are not replayed. */
    private int replayHorizonSeconds = 300;

    /** Maximum concurrent streams per user. Exceeding returns 429. */
    private int maxConcurrentPerUser = 3;

    /** SseEmitter timeout in ms. Should exceed heartbeatIntervalSeconds to avoid spurious timeouts. */
    private long emitterTimeoutMs = 65_000L;

    public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(int v) { this.heartbeatIntervalSeconds = v; }

    public int getReplayBufferSize() { return replayBufferSize; }
    public void setReplayBufferSize(int v) { this.replayBufferSize = v; }

    public int getReplayHorizonSeconds() { return replayHorizonSeconds; }
    public void setReplayHorizonSeconds(int v) { this.replayHorizonSeconds = v; }

    public int getMaxConcurrentPerUser() { return maxConcurrentPerUser; }
    public void setMaxConcurrentPerUser(int v) { this.maxConcurrentPerUser = v; }

    public long getEmitterTimeoutMs() { return emitterTimeoutMs; }
    public void setEmitterTimeoutMs(long v) { this.emitterTimeoutMs = v; }
}
