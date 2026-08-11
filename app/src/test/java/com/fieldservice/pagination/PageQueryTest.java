package com.fieldservice.pagination;

import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.SortAllowList;
import com.fieldservice.platform.pagination.SortField;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PageQuery construction, size clamping, and sort parsing.
 */
class PageQueryTest {

    // -------------------------------------------------------------------------
    // Size clamping
    // -------------------------------------------------------------------------

    @Test
    void size_below_minimum_is_clamped_to_one() {
        PageQuery q = new PageQuery(0, 0, List.of(), null);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    void size_exactly_at_max_is_accepted() {
        PageQuery q = new PageQuery(0, PageQuery.MAX_SIZE, List.of(), null);
        assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void size_above_max_is_clamped_to_max() {
        PageQuery q = new PageQuery(0, PageQuery.MAX_SIZE + 1, List.of(), null);
        assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void extremely_large_size_is_still_clamped() {
        PageQuery q = new PageQuery(0, Integer.MAX_VALUE, List.of(), null);
        assertThat(q.size()).isEqualTo(PageQuery.MAX_SIZE);
    }

    @Test
    void negative_size_is_clamped_to_one() {
        PageQuery q = new PageQuery(0, -999, List.of(), null);
        assertThat(q.size()).isEqualTo(1);
    }

    @Test
    void default_size_constant_is_twenty() {
        assertThat(PageQuery.DEFAULT_SIZE).isEqualTo(20);
    }

    @Test
    void max_size_constant_is_fifty() {
        assertThat(PageQuery.MAX_SIZE).isEqualTo(50);
    }

    // -------------------------------------------------------------------------
    // Page clamping
    // -------------------------------------------------------------------------

    @Test
    void negative_page_is_clamped_to_zero() {
        PageQuery q = new PageQuery(-1, 20, List.of(), null);
        assertThat(q.page()).isZero();
    }

    @Test
    void page_zero_is_valid() {
        PageQuery q = new PageQuery(0, 20, List.of(), null);
        assertThat(q.page()).isZero();
    }

    // -------------------------------------------------------------------------
    // Sort parsing via SortField
    // -------------------------------------------------------------------------

    @Test
    void sort_token_without_direction_defaults_to_desc() {
        SortField sf = SortField.parse("createdAt");
        assertThat(sf.field()).isEqualTo("createdAt");
        assertThat(sf.direction()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void sort_token_with_asc_direction_is_parsed() {
        SortField sf = SortField.parse("title:ASC");
        assertThat(sf.field()).isEqualTo("title");
        assertThat(sf.direction()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void sort_token_with_desc_direction_is_parsed() {
        SortField sf = SortField.parse("priority:DESC");
        assertThat(sf.direction()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void blank_sort_token_throws_invalid_sort_exception() {
        assertThatThrownBy(() -> SortField.parse(""))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void invalid_direction_throws_invalid_sort_exception() {
        assertThatThrownBy(() -> SortField.parse("title:RANDOM"))
                .isInstanceOf(InvalidSortException.class);
    }

    // -------------------------------------------------------------------------
    // SortAllowList injection defence
    // -------------------------------------------------------------------------

    @Test
    void unknown_sort_field_is_rejected_by_allow_list() {
        SortAllowList list = SortAllowList.of("title", "title");
        assertThatThrownBy(() -> list.resolvePersistentName("unknownField"))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void sql_injection_attempt_in_sort_field_is_rejected() {
        SortAllowList list = SortAllowList.of("title", "title");
        assertThatThrownBy(() -> list.resolvePersistentName("'; DROP TABLE work_order; --"))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void known_sort_field_resolves_to_persistent_name() {
        SortAllowList list = SortAllowList.of("createdAt", "createdAt");
        assertThat(list.resolvePersistentName("createdAt")).isEqualTo("createdAt");
    }

    // -------------------------------------------------------------------------
    // Cursor presence
    // -------------------------------------------------------------------------

    @Test
    void null_cursor_means_no_cursor() {
        PageQuery q = new PageQuery(0, 20, List.of(), null);
        assertThat(q.hasCursor()).isFalse();
    }

    @Test
    void non_blank_cursor_is_detected() {
        PageQuery q = new PageQuery(0, 20, List.of(), "some.cursor");
        assertThat(q.hasCursor()).isTrue();
    }

    @Test
    void blank_cursor_is_not_treated_as_cursor() {
        PageQuery q = new PageQuery(0, 20, List.of(), "   ");
        assertThat(q.hasCursor()).isFalse();
    }
}
