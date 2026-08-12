package com.fieldservice.photoanalysis.internal;

import org.springframework.stereotype.Component;

/**
 * Validates image content by inspecting magic bytes — not the client-supplied
 * Content-Type header or file extension.
 *
 * <p>Magic byte signatures:
 * <ul>
 *   <li>JPEG: {@code FF D8 FF}</li>
 *   <li>PNG:  {@code 89 50 4E 47 0D 0A 1A 0A}</li>
 *   <li>WebP: {@code 52 49 46 46 xx xx xx xx 57 45 42 50} (RIFF....WEBP)</li>
 * </ul>
 *
 * <p>Validation is performed before any AI processing. A file with an image extension
 * but non-image bytes is rejected here, before the gateway is called.
 */
@Component
public class ImageContentValidator {

    /** Minimum bytes needed to determine the image type (12 for WebP RIFF+WEBP header). */
    public static final int MAGIC_BYTE_READ_LENGTH = 12;

    // JPEG: starts with FF D8 FF
    private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

    // PNG: starts with 89 50 4E 47 0D 0A 1A 0A
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    // WebP: bytes 0-3 = RIFF, bytes 8-11 = WEBP
    private static final byte[] RIFF_MAGIC = { 0x52, 0x49, 0x46, 0x46 };
    private static final byte[] WEBP_MAGIC = { 0x57, 0x45, 0x42, 0x50 };

    /**
     * Validates that the leading bytes match a supported image type.
     *
     * @param bytes leading bytes of the stored object (at least {@link #MAGIC_BYTE_READ_LENGTH})
     * @throws UnsupportedImageTypeException if the bytes do not match JPEG, PNG, or WebP
     * @throws UnsupportedImageTypeException if the byte array is null or too short
     */
    public void validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedImageTypeException("zero-length or missing object");
        }

        if (isJpeg(bytes)) return;
        if (isPng(bytes))  return;
        if (isWebp(bytes)) return;

        throw new UnsupportedImageTypeException(
                "content does not match any allowed image type (JPEG, PNG, WebP)");
    }

    /**
     * Returns the canonical content type for the given bytes, or null if not recognised.
     * Does not throw — callers needing strict validation should call {@link #validate} instead.
     */
    public String detectContentType(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        if (isJpeg(bytes)) return "image/jpeg";
        if (isPng(bytes))  return "image/png";
        if (isWebp(bytes)) return "image/webp";
        return null;
    }

    private static boolean isJpeg(byte[] bytes) {
        return startsWith(bytes, JPEG_MAGIC);
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= PNG_MAGIC.length && startsWith(bytes, PNG_MAGIC);
    }

    private static boolean isWebp(byte[] bytes) {
        if (bytes.length < 12) return false;
        return startsWith(bytes, RIFF_MAGIC)
                && bytes[8] == WEBP_MAGIC[0]
                && bytes[9] == WEBP_MAGIC[1]
                && bytes[10] == WEBP_MAGIC[2]
                && bytes[11] == WEBP_MAGIC[3];
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    // ── Exceptions ────────────────────────────────────────────────────────────

    public static class UnsupportedImageTypeException extends RuntimeException {
        public UnsupportedImageTypeException(String detail) {
            super("Unsupported image type: " + detail);
        }
    }
}
