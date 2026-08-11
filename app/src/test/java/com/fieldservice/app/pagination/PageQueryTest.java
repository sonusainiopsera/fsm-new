package com.fieldservice.app.pagination;

import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.api.exception.InvalidSortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PageQuery} parameter binding and size clamping.
 */
class PageQueryTest {

    private static final SortAllowList ALLOW = SortAllowList.of(
            Map.of("createdAt", "createdAt", "priority", "priority"),
            "createdAt");

    @Test
    @DisplayName("defaults: page=0, size=20")
    void defaults() {
        PageQuery q = PageQuery.of(null, null, null);
        assertThat(q.page()).isEqualTo(0);
        assertThat(q.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("size is hard-capped at 50")
    void sizeClamped_at50() {
        PageQuery q = PageQuery.of(0, 9999, null);
        assertThat(q.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("size of exactly 50 is accepted")
    void sizeExactly50_accepted() {
        PageQuery q = PageQuery.of(0, 50, null);
        assertThat(q.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("size of 51 is clamped to 50")
    void size51_clampedTo50() {
        PageQuery q = PageQuery.of(0, 51, null);
        assertThat(q.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("size of 1 is accepted as minimum")
    void sizeMinimum1() {
        PageQuery q = PageQuery.of(0, 1, null);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("size of 0 is floored to 1")
    void sizeZero_flooredToOne() {
        PageQuery q = PageQuery.of(0, 0, null);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("negative page is floored to 0")
    void negativePage_flooredToZero() {
        PageQuery q = PageQuery.of(-5, 20, null);
        assertThat(q.page()).isEqualTo(0);
    }

    @Test
    @DisplayName("toPageable produces correct Spring Data Pageable")
    void toPageable_setsPageAndSize() {
        PageQuery q = PageQuery.of(3, 15, null);
        Pageable pageable = q.toPageable(ALLOW);
        assertThat(pageable.getPageNumber()).isEqualTo(3);
        assertThat(pageable.getPageSize()).isEqualTo(15);
    }

    @Test
    @DisplayName("toPageable always appends id tie-break")
    void toPageable_appendsIdTieBreak() {
        PageQuery q = PageQuery.of(0, 20, "createdAt:desc");
        Pageable pageable = q.toPageable(ALLOW);
        boolean hasId = pageable.getSort().stream()
                .anyMatch(o -> "id".equals(o.getProperty()));
        assertThat(hasId).isTrue();
    }

    @Test
    @DisplayName("invalid sort field → InvalidSortException")
    void invalidSortField_throwsInvalidSortException() {
        PageQuery q = PageQuery.of(0, 20, "injectedField:asc");
        assertThatThrownBy(() -> q.toPageable(ALLOW))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("injectedField");
    }

    @Test
    @DisplayName("invalid sort direction → InvalidSortException")
    void invalidSortDirection_throwsInvalidSortException() {
        PageQuery q = PageQuery.of(0, 20, "createdAt:INVALID");
        assertThatThrownBy(() -> q.toPageable(ALLOW))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    @DisplayName("MAX_SIZE constant is 50")
    void maxSizeConstant() {
        assertThat(PageQuery.MAX_SIZE).isEqualTo(50);
    }

    @Test
    @DisplayName("DEFAULT_SIZE constant is 20")
    void defaultSizeConstant() {
        assertThat(PageQuery.DEFAULT_SIZE).isEqualTo(20);
    }
}
