package com.fieldservice.aigateway.api;

/**
 * A single chunk in a streaming completion response.
 *
 * <p>The stream is complete when {@link #isLast()} is {@code true}. If the
 * connection resets mid-stream, a chunk with {@link #isError()} {@code true} is
 * emitted and no further chunks follow — callers must discard partial content.
 *
 * @param delta   the text fragment for this chunk (may be empty for the final/error chunk)
 * @param isLast  true when this is the final (possibly empty) chunk in the stream
 * @param isError true when the stream was interrupted before completion
 */
public record AiStreamChunk(String delta, boolean isLast, boolean isError) {

    public static AiStreamChunk of(String delta) {
        return new AiStreamChunk(delta, false, false);
    }

    public static AiStreamChunk last() {
        return new AiStreamChunk("", true, false);
    }

    public static AiStreamChunk error() {
        return new AiStreamChunk("", true, true);
    }
}
