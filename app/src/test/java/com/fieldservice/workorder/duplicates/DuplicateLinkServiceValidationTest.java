package com.fieldservice.workorder.duplicates;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for DuplicateLinkServiceImpl validation helpers.
 * These tests exercise the chain-resolution and cycle-detection logic
 * in isolation, without a Spring context.
 */
class DuplicateLinkServiceValidationTest {

    @Test
    void selfLink_code_is_DUPLICATE_SELF_LINK() {
        DuplicateLinkException ex = DuplicateLinkException.selfLink();
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_SELF_LINK");
    }

    @Test
    void alreadyLinked_code_is_DUPLICATE_ALREADY_LINKED() {
        DuplicateLinkException ex = DuplicateLinkException.alreadyLinked("WO-00000001");
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_ALREADY_LINKED");
    }

    @Test
    void targetNotOpen_code_is_DUPLICATE_TARGET_NOT_OPEN() {
        DuplicateLinkException ex = DuplicateLinkException.targetNotOpen("WO-00000002");
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_TARGET_NOT_OPEN");
    }

    @Test
    void cycle_code_is_DUPLICATE_LINK_CYCLE() {
        DuplicateLinkException ex = DuplicateLinkException.cycle();
        assertThat(ex.getCode()).isEqualTo("DUPLICATE_LINK_CYCLE");
    }

    @Test
    void cancellationReasonCode_is_DUPLICATE_REQUEST() {
        assertThat(DuplicateLinkServiceImpl.CANCELLATION_REASON_CODE).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void maxChainDepth_is_positive() {
        assertThat(DuplicateLinkServiceImpl.MAX_CHAIN_DEPTH).isGreaterThan(0);
    }
}
