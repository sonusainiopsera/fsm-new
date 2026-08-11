package com.fieldservice.contract.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable contract assertion helpers for the WO-204 API conformance suite.
 *
 * <h2>Envelope shapes</h2>
 * <pre>
 * Collection:
 *   { "data": [...], "page": { "number", "size", "totalElements", "totalPages" },
 *     "links": { "next": "...", "prev": null } }
 *
 * Error:
 *   { "code": "...", "message": "...", "fieldErrors": [...], "traceId": "..." }
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * MvcResult result = mockMvc.perform(...).andReturn();
 * ApiAssertions.assertPageEnvelope(result.getResponse());
 * ApiAssertions.assertPageMeta(result.getResponse(), 0, 10, 100);
 * ApiAssertions.assertErrorShape(result.getResponse(), "NOT_FOUND");
 * ApiAssertions.assertNoInternals(result.getResponse());
 * }</pre>
 */
public final class ApiAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] FORBIDDEN_INTERNAL_SUBSTRINGS = {
        "Exception", "StackTrace", "stackTrace", "at com.", "at org.", "at java.",
        "SQLException", "org.hibernate", "org.springframework", "NullPointerException",
        "sun.reflect", "java.lang.reflect"
    };

    private ApiAssertions() {}

    // -------------------------------------------------------------------------
    // Collection envelope assertion
    // -------------------------------------------------------------------------

    /**
     * Asserts the collection response envelope contains the required three top-level keys:
     * {@code data} (array), {@code page} (object), and {@code links} (object).
     */
    public static void assertPageEnvelope(MockHttpServletResponse response) {
        JsonNode root = parse(response);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(root.has("data"))
            .as("response must contain 'data' array").isTrue();
        soft.assertThat(root.has("page"))
            .as("response must contain 'page' object").isTrue();
        soft.assertThat(root.has("links"))
            .as("response must contain 'links' object").isTrue();

        if (root.has("data")) {
            soft.assertThat(root.get("data").isArray())
                .as("'data' must be a JSON array").isTrue();
        }
        if (root.has("page")) {
            JsonNode page = root.get("page");
            soft.assertThat(page.has("number"))
                .as("'page.number' must be present").isTrue();
            soft.assertThat(page.has("size"))
                .as("'page.size' must be present").isTrue();
            soft.assertThat(page.has("totalElements"))
                .as("'page.totalElements' must be present").isTrue();
            soft.assertThat(page.has("totalPages"))
                .as("'page.totalPages' must be present").isTrue();
        }
        if (root.has("links")) {
            soft.assertThat(root.get("links").isObject())
                .as("'links' must be a JSON object").isTrue();
        }
        soft.assertAll();
    }

    /**
     * Asserts {@code links.next} is absent (null or missing), as required on the final page.
     */
    public static void assertNoNextLink(MockHttpServletResponse response) {
        JsonNode root = parse(response);
        JsonNode links = root.path("links");
        boolean nextAbsent = links.isMissingNode()
            || links.path("next").isNull()
            || links.path("next").isMissingNode();
        assertThat(nextAbsent)
            .as("links.next must be absent (null) on the final page")
            .isTrue();
    }

    /**
     * Asserts specific {@code page} metadata values.
     */
    public static void assertPageMeta(MockHttpServletResponse response,
                                      int expectedNumber, int expectedSize,
                                      long expectedTotalElements) {
        JsonNode root = parse(response);
        JsonNode page = root.path("page");
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(page.path("number").asInt())
            .as("page.number").isEqualTo(expectedNumber);
        soft.assertThat(page.path("size").asInt())
            .as("page.size").isEqualTo(expectedSize);
        soft.assertThat(page.path("totalElements").asLong())
            .as("page.totalElements").isEqualTo(expectedTotalElements);
        soft.assertAll();
    }

    // -------------------------------------------------------------------------
    // Error envelope assertion
    // -------------------------------------------------------------------------

    /**
     * Asserts the error response envelope has the required shape:
     * {@code code}, {@code message}, {@code fieldErrors} (array), {@code traceId} (non-blank).
     */
    public static void assertErrorShape(MockHttpServletResponse response, String expectedCode) {
        assertErrorShape(response, expectedCode, new String[0]);
    }

    /**
     * Asserts the error response envelope and, optionally, that specific field names
     * appear in the {@code fieldErrors} array.
     */
    public static void assertErrorShape(MockHttpServletResponse response, String expectedCode,
                                        String... expectedFieldNames) {
        JsonNode root = parse(response);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(root.path("code").asText())
            .as("error code").isEqualTo(expectedCode);
        soft.assertThat(root.has("message"))
            .as("'message' must be present").isTrue();
        soft.assertThat(root.path("message").asText())
            .as("'message' must not be blank").isNotBlank();
        soft.assertThat(root.has("fieldErrors"))
            .as("'fieldErrors' array must be present").isTrue();
        soft.assertThat(root.get("fieldErrors").isArray())
            .as("'fieldErrors' must be a JSON array").isTrue();
        soft.assertThat(root.has("traceId"))
            .as("'traceId' must be present").isTrue();
        soft.assertThat(root.path("traceId").asText())
            .as("'traceId' must not be blank").isNotBlank();
        soft.assertAll();

        if (expectedFieldNames.length > 0) {
            JsonNode fieldErrors = root.path("fieldErrors");
            List<String> actualFields = new java.util.ArrayList<>();
            for (JsonNode fe : fieldErrors) {
                actualFields.add(fe.path("field").asText());
            }
            assertThat(actualFields)
                .as("fieldErrors must contain the named fields")
                .contains(expectedFieldNames);
        }
    }

    // -------------------------------------------------------------------------
    // Security / information-leakage assertion
    // -------------------------------------------------------------------------

    /**
     * Asserts the response body contains no stack trace, internal class names, or
     * raw SQL fragments — i.e., the API never leaks implementation details.
     */
    public static void assertNoInternals(MockHttpServletResponse response) {
        String body = body(response);
        for (String forbidden : FORBIDDEN_INTERNAL_SUBSTRINGS) {
            assertThat(body)
                .as("Response body must not contain internal substring: '%s'", forbidden)
                .doesNotContain(forbidden);
        }
    }

    /**
     * Convenience overload accepting a {@link MvcResult}.
     */
    public static void assertNoInternals(MvcResult result) {
        assertNoInternals(result.getResponse());
    }

    /**
     * Asserts the response is an empty collection (data[] empty, totalElements=0, no next link).
     */
    public static void assertEmptyCollection(MockHttpServletResponse response) {
        assertPageEnvelope(response);
        JsonNode root = parse(response);
        assertThat(root.path("data").size()).as("data must be empty").isZero();
        assertThat(root.path("page").path("totalElements").asLong())
            .as("totalElements must be 0").isZero();
        assertNoNextLink(response);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static JsonNode parse(MockHttpServletResponse response) {
        try {
            return MAPPER.readTree(response.getContentAsString());
        } catch (Exception e) {
            throw new AssertionError("Could not parse response as JSON: " + body(response), e);
        }
    }

    static String body(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }
}
