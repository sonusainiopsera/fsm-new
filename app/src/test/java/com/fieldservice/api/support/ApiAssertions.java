package com.fieldservice.api.support;

import com.fieldservice.platform.api.ErrorEnvelope;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Reusable JSON-path assertion helpers for the P0 contract conformance suite (WO-204).
 *
 * <p>These helpers assert the load-bearing API conventions:
 * <ul>
 *   <li>{@link #assertEnvelope} — collection responses carry {@code data}, {@code page}, {@code links}</li>
 *   <li>{@link #assertPageMeta} — specific numeric values within {@code page}</li>
 *   <li>{@link #assertErrorShape} — error responses carry {@code code}, {@code message},
 *       {@code fieldErrors[]}, {@code traceId}</li>
 *   <li>{@link #assertLastPage} — no {@code links.next} on the final page</li>
 *   <li>{@link #assertEmptyEnvelope} — empty collection: data=[], totalElements=0, no next link</li>
 * </ul>
 */
public final class ApiAssertions {

    private ApiAssertions() {}

    /**
     * Asserts the platform collection envelope shape: data[], page{number,size,totalElements,totalPages},
     * links{next,prev}.
     *
     * <p>Does NOT assert specific numeric values — use {@link #assertPageMeta} for that.
     */
    public static ResultActions assertEnvelope(ResultActions result) throws Exception {
        return result
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.page.number").isNumber())
                .andExpect(jsonPath("$.page.size").isNumber())
                .andExpect(jsonPath("$.page.totalElements").isNumber())
                .andExpect(jsonPath("$.page.totalPages").isNumber())
                .andExpect(jsonPath("$.links").exists());
    }

    /**
     * Asserts exact {@code page} metadata values within a collection response.
     */
    public static ResultActions assertPageMeta(ResultActions result,
                                               int number, int size, long totalElements) throws Exception {
        return result
                .andExpect(jsonPath("$.page.number").value(number))
                .andExpect(jsonPath("$.page.size").value(size))
                .andExpect(jsonPath("$.page.totalElements").value(totalElements));
    }

    /**
     * Asserts that an error response conforms to the {@link ErrorEnvelope} contract:
     * {@code code}, {@code message}, {@code fieldErrors[]}, {@code traceId} are all present.
     *
     * @param expectedCode     the stable error code (e.g. {@link ErrorEnvelope.Code#VALIDATION_FAILED})
     * @param minFieldErrors   minimum number of per-field error entries ({@code 0} for non-validation errors)
     */
    public static ResultActions assertErrorShape(ResultActions result,
                                                  String expectedCode,
                                                  int minFieldErrors) throws Exception {
        result = result
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.message", not(emptyOrNullString())))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId", notNullValue()));
        if (minFieldErrors > 0) {
            result = result.andExpect(jsonPath("$.fieldErrors", hasSize(minFieldErrors)));
        }
        return result;
    }

    /**
     * Asserts that an error response has no leaked internals (no stack trace, exception class,
     * SQL fragment, or resource identifier).
     */
    public static ResultActions assertNoInternalLeak(ResultActions result) throws Exception {
        return result
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    /**
     * Asserts that a collection response is the last page: {@code links.next} is absent or null.
     */
    public static ResultActions assertLastPage(ResultActions result) throws Exception {
        return result.andExpect(
                jsonPath("$.links.next", is(nullValue())));
    }

    /**
     * Asserts that a collection response represents an empty result set:
     * {@code data} is empty, {@code totalElements} is zero, no {@code next} link.
     */
    public static ResultActions assertEmptyEnvelope(ResultActions result) throws Exception {
        return result
                .andExpect(jsonPath("$.data", empty()))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.links.next", is(nullValue())));
    }
}
