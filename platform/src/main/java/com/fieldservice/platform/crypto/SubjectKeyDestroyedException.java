package com.fieldservice.platform.crypto;

/**
 * Thrown by {@link SubjectKeyManager#resolve} when the requested key version has
 * been irrecoverably destroyed.  Callers that receive this exception must return
 * {@link EnvelopeEncryptedStringConverter#UNRECOVERABLE_MARKER} rather than re-throwing,
 * so historical reads, reports and Envers queries do not break.
 */
public class SubjectKeyDestroyedException extends RuntimeException {

    public SubjectKeyDestroyedException(SubjectRef subject, int keyVersion) {
        super("Data key destroyed for subject " + subject.subjectType()
                + "/" + subject.subjectId() + " version " + keyVersion);
    }
}
