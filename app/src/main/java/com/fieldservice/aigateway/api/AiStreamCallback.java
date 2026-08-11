package com.fieldservice.aigateway.api;

/** Callback for streaming completions — each token chunk is delivered in order. */
@FunctionalInterface
public interface AiStreamCallback {
    void onToken(String tokenChunk);

    default void onComplete() {}
    default void onError(Throwable cause) {}
}
