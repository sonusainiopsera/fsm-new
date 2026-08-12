package com.fieldservice.photoanalysis;

import com.fieldservice.photoanalysis.internal.ImageContentValidator;
import com.fieldservice.photoanalysis.internal.ImageContentValidator.UnsupportedImageTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ImageContentValidator} magic-byte detection.
 * Validation must occur before any storage read or provider call.
 */
class ImageContentValidatorTest {

    private final ImageContentValidator validator = new ImageContentValidator();

    // ── JPEG ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("JPEG detection")
    class JpegDetection {

        @Test
        @DisplayName("valid JPEG magic bytes pass validation")
        void validJpeg() {
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00};
            validator.validate(jpeg); // must not throw
        }

        @Test
        @DisplayName("detectContentType returns image/jpeg for JPEG bytes")
        void detectJpeg() {
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x18, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00};
            assertThat(validator.detectContentType(jpeg)).isEqualTo("image/jpeg");
        }
    }

    // ── PNG ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PNG detection")
    class PngDetection {

        @Test
        @DisplayName("valid PNG magic bytes pass validation")
        void validPng() {
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
            validator.validate(png);
        }

        @Test
        @DisplayName("detectContentType returns image/png for PNG bytes")
        void detectPng() {
            byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
            assertThat(validator.detectContentType(png)).isEqualTo("image/png");
        }
    }

    // ── WebP ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("WebP detection")
    class WebpDetection {

        @Test
        @DisplayName("valid WebP magic bytes pass validation")
        void validWebp() {
            byte[] webp = {'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};
            validator.validate(webp);
        }

        @Test
        @DisplayName("detectContentType returns image/webp for WebP bytes")
        void detectWebp() {
            byte[] webp = {'R', 'I', 'F', 'F', 0x30, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};
            assertThat(validator.detectContentType(webp)).isEqualTo("image/webp");
        }
    }

    // ── Rejection ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Rejection of non-image bytes")
    class Rejection {

        @Test
        @DisplayName("null input is rejected")
        void nullRejected() {
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(UnsupportedImageTypeException.class);
        }

        @Test
        @DisplayName("zero-length byte array is rejected")
        void emptyRejected() {
            assertThatThrownBy(() -> validator.validate(new byte[0]))
                    .isInstanceOf(UnsupportedImageTypeException.class);
        }

        @Test
        @DisplayName("file with .jpg extension but PDF magic bytes is rejected")
        void jpgExtensionPdfBytes() {
            // PDF magic: %PDF
            byte[] pdf = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, 0x00, 0x00, 0x00};
            assertThatThrownBy(() -> validator.validate(pdf))
                    .isInstanceOf(UnsupportedImageTypeException.class);
        }

        @Test
        @DisplayName("file with executable PE header is rejected")
        void executableRejected() {
            // Windows PE: MZ
            byte[] pe = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00};
            assertThatThrownBy(() -> validator.validate(pe))
                    .isInstanceOf(UnsupportedImageTypeException.class);
        }

        @Test
        @DisplayName("GIF bytes are rejected (not in allow-list)")
        void gifRejected() {
            byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00};
            assertThatThrownBy(() -> validator.validate(gif))
                    .isInstanceOf(UnsupportedImageTypeException.class);
        }

        @Test
        @DisplayName("RIFF header without WEBP marker is rejected")
        void riffWithoutWebpRejected() {
            // RIFF with AVI instead of WEBP
            byte[] avi = {'R', 'I', 'F', 'F', 0x10, 0x00, 0x00, 0x00, 'A', 'V', 'I', ' '};
            assertThatThrownBy(() -> validator.validate(avi))
                    .isInstanceOf(UnsupportedImageTypeException.class);
        }

        @Test
        @DisplayName("detectContentType returns null for unknown bytes (does not throw)")
        void detectUnknownReturnsNull() {
            byte[] unknown = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B};
            assertThat(validator.detectContentType(unknown)).isNull();
        }
    }
}
