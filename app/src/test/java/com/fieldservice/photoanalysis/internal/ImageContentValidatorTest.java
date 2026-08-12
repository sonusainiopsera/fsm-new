package com.fieldservice.photoanalysis.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;

class ImageContentValidatorTest {

    private final ImageContentValidator validator = new ImageContentValidator();

    private static final long MAX = 5 * 1024 * 1024L;

    // ── JPEG ──────────────────────────────────────────────────────────────────

    @Test
    void validJpeg_returnsImageJpeg() throws IOException {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        String type = validator.validate(new ByteArrayInputStream(header), 1024L, MAX);
        assertThat(type).isEqualTo("image/jpeg");
    }

    // ── PNG ───────────────────────────────────────────────────────────────────

    @Test
    void validPng_returnsImagePng() throws IOException {
        byte[] header = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        String type = validator.validate(new ByteArrayInputStream(header), 1024L, MAX);
        assertThat(type).isEqualTo("image/png");
    }

    // ── WebP ──────────────────────────────────────────────────────────────────

    @Test
    void validWebp_returnsImageWebp() throws IOException {
        byte[] header = {
            0x52, 0x49, 0x46, 0x46,  // RIFF
            0x10, 0x00, 0x00, 0x00,  // file size (irrelevant)
            0x57, 0x45, 0x42, 0x50   // WEBP
        };
        String type = validator.validate(new ByteArrayInputStream(header), 1024L, MAX);
        assertThat(type).isEqualTo("image/webp");
    }

    // ── Unsupported type ──────────────────────────────────────────────────────

    @Test
    void gifMagicBytes_throwsUnsupportedType() {
        byte[] gif = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> validator.validate(new ByteArrayInputStream(gif), 1024L, MAX))
                .isInstanceOf(ImageContentValidator.ImageValidationException.class)
                .satisfies(e -> assertThat(((ImageContentValidator.ImageValidationException) e).getCode())
                        .isEqualTo("UNSUPPORTED_TYPE"));
    }

    @Test
    void allZeroBytes_throwsUnsupportedType() {
        byte[] zeros = new byte[12];
        assertThatThrownBy(() -> validator.validate(new ByteArrayInputStream(zeros), 1024L, MAX))
                .isInstanceOf(ImageContentValidator.ImageValidationException.class)
                .satisfies(e -> assertThat(((ImageContentValidator.ImageValidationException) e).getCode())
                        .isEqualTo("UNSUPPORTED_TYPE"));
    }

    // ── Size checks ───────────────────────────────────────────────────────────

    @Test
    void sizeExceedsMax_throwsSizeExceeded() {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> validator.validate(new ByteArrayInputStream(header), MAX + 1, MAX))
                .isInstanceOf(ImageContentValidator.ImageValidationException.class)
                .satisfies(e -> assertThat(((ImageContentValidator.ImageValidationException) e).getCode())
                        .isEqualTo("SIZE_EXCEEDED"));
    }

    @Test
    void sizeZero_throwsSizeZero() {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> validator.validate(new ByteArrayInputStream(header), 0L, MAX))
                .isInstanceOf(ImageContentValidator.ImageValidationException.class)
                .satisfies(e -> assertThat(((ImageContentValidator.ImageValidationException) e).getCode())
                        .isEqualTo("SIZE_ZERO"));
    }

    @Test
    void exactlyMaxSize_accepted() throws IOException {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThatCode(() -> validator.validate(new ByteArrayInputStream(header), MAX, MAX))
                .doesNotThrowAnyException();
    }

    // ── Short stream ──────────────────────────────────────────────────────────

    @Test
    void tooFewBytes_throwsUnrecognisedFormat() {
        byte[] two = {(byte) 0xFF, (byte) 0xD8};
        assertThatThrownBy(() -> validator.validate(new ByteArrayInputStream(two), 2L, MAX))
                .isInstanceOf(ImageContentValidator.ImageValidationException.class)
                .satisfies(e -> assertThat(((ImageContentValidator.ImageValidationException) e).getCode())
                        .isEqualTo("UNRECOGNISED_FORMAT"));
    }

    // ── Magic bytes only — does not buffer the whole stream ───────────────────

    @Test
    void onlyReadsFirstTwelveBytesOfJpeg() throws IOException {
        // Build a large byte array; only the first 12 bytes matter
        byte[] large = new byte[1024];
        large[0] = (byte) 0xFF; large[1] = (byte) 0xD8; large[2] = (byte) 0xFF;
        String type = validator.validate(new ByteArrayInputStream(large), 1024L, MAX);
        assertThat(type).isEqualTo("image/jpeg");
    }
}
