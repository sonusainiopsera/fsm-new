package com.fieldservice.photoanalysis.internal;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Validates photo content type from magic bytes, not from client-supplied headers or extensions.
 *
 * <p>AC-1: validation runs before any processing. The caller must supply an InputStream
 * positioned at byte 0. At most 12 bytes are read — the object is never buffered in heap.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>JPEG: FF D8 FF</li>
 *   <li>PNG:  89 50 4E 47 0D 0A 1A 0A (8 bytes)</li>
 *   <li>WebP: 52 49 46 46 xx xx xx xx 57 45 42 50 (bytes 0–3 and 8–11)</li>
 * </ul>
 *
 * <p>A file with an allowed extension but non-image bytes (e.g. an executable with a
 * {@code .jpg} extension) is rejected at this layer before any provider call.
 */
@Component
class ImageContentValidator {

    static final int MAX_MAGIC_BYTES = 12;

    private static final byte[] JPEG_MAGIC  = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC   = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] WEBP_RIFF   = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MARKER = {0x57, 0x45, 0x42, 0x50};

    /**
     * Reads magic bytes from the stream and returns the detected MIME type.
     *
     * @param stream  positioned at byte 0; only the first {@link #MAX_MAGIC_BYTES} bytes are read
     * @param maxBytes configured maximum file size (AC-1)
     * @param declaredSizeBytes file size reported by the caller; checked against maxBytes before stream read
     * @return detected MIME type string
     * @throws ImageValidationException if bytes do not match any allowed type or the size exceeds limit
     * @throws IOException              on stream read failure
     */
    String validate(InputStream stream, long declaredSizeBytes, long maxBytes)
            throws IOException {
        if (declaredSizeBytes <= 0) {
            throw new ImageValidationException("SIZE_ZERO", "File must not be empty.");
        }
        if (declaredSizeBytes > maxBytes) {
            throw new ImageValidationException("SIZE_EXCEEDED",
                    "File size " + declaredSizeBytes + " exceeds the maximum of " + maxBytes + " bytes.");
        }

        byte[] header = new byte[MAX_MAGIC_BYTES];
        int bytesRead = stream.readNBytes(header, 0, MAX_MAGIC_BYTES);
        if (bytesRead < 3) {
            throw new ImageValidationException("UNRECOGNISED_FORMAT",
                    "File is too small to determine type.");
        }

        if (startsWith(header, bytesRead, JPEG_MAGIC)) {
            return "image/jpeg";
        }
        if (bytesRead >= 8 && startsWith(header, bytesRead, PNG_MAGIC)) {
            return "image/png";
        }
        if (bytesRead >= 12
                && startsWith(header, bytesRead, WEBP_RIFF)
                && startsWith(Arrays.copyOfRange(header, 8, 12), 4, WEBP_MARKER)) {
            return "image/webp";
        }
        throw new ImageValidationException("UNSUPPORTED_TYPE",
                "File type is not recognised as JPEG, PNG or WebP.");
    }

    private static boolean startsWith(byte[] data, int dataLen, byte[] prefix) {
        if (dataLen < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    // ── Exception ──────────────────────────────────────────────────────────────

    static final class ImageValidationException extends RuntimeException {
        private final String code;

        ImageValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        String getCode() { return code; }
    }
}
