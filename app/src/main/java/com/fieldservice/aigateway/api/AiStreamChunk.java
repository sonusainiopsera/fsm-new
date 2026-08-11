package com.fieldservice.aigateway.api;

/**
 * A single chunk emitted by a streaming completion call.
 *
 * @param delta incremental text fragment
 * @param last  true when this chunk terminates the stream (finish_reason == stop)
 */
public record AiStreamChunk(String delta, boolean last) {}
