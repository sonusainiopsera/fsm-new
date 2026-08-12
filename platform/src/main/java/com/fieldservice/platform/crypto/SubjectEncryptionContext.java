package com.fieldservice.platform.crypto;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Thread-local holder that carries the current {@link SubjectRef} through a JPA
 * persist or merge operation so that {@link EnvelopeEncryptedStringConverter} can
 * look up the correct per-subject data key.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   SubjectEncryptionContext.runWith(SubjectRef.of("TECHNICIAN", technicianId), () ->
 *       positionRepository.save(entity));
 * }</pre>
 *
 * <p>The context is automatically cleared after the work completes, even on exception.
 * Nesting is not supported: calling {@code runWith} inside another {@code runWith} for a
 * different subject overwrites the outer subject for the duration of the inner call.
 */
public final class SubjectEncryptionContext {

    private static final ThreadLocal<SubjectRef> HOLDER = new ThreadLocal<>();

    private SubjectEncryptionContext() {}

    /** Sets the current thread's subject, runs {@code work}, then always clears. */
    public static void runWith(SubjectRef subject, Runnable work) {
        HOLDER.set(subject);
        try {
            work.run();
        } finally {
            HOLDER.remove();
        }
    }

    /** Sets the current thread's subject, calls {@code work}, then always clears. */
    public static <T> T callWith(SubjectRef subject, Callable<T> work) throws Exception {
        HOLDER.set(subject);
        try {
            return work.call();
        } finally {
            HOLDER.remove();
        }
    }

    /** Returns the current thread's subject, or empty if none is set. */
    public static Optional<SubjectRef> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * Like {@link #callWith} but wraps checked exceptions in {@link RuntimeException}
     * for use in test code and lambda-heavy service paths.
     */
    public static <T> T callWithUnchecked(SubjectRef subject, java.util.function.Supplier<T> work) {
        HOLDER.set(subject);
        try {
            return work.get();
        } finally {
            HOLDER.remove();
        }
    }

    /** Explicitly clears the current thread's subject (called by framework code). */
    public static void clear() {
        HOLDER.remove();
    }
}
