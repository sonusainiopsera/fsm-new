package com.fieldservice.app.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link TraceIdFilter}.
 */
@ExtendWith(MockitoExtension.class)
class TraceIdFilterTest {

    private TraceIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void generatesNewTraceIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String traceIdInResponse = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        assertThat(traceIdInResponse).isNotBlank();
        // Should be a UUID format
        assertThat(traceIdInResponse).matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        );
    }

    @Test
    void propagatesIncomingTraceIdHeader() throws Exception {
        String incomingTraceId = "upstream-trace-12345";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, incomingTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo(incomingTraceId);
    }

    @Test
    void traceIdIsPlacedInMdcDuringFilterExecution(@Mock FilterChain mockChain) throws Exception {
        String incomingTraceId = "mdc-test-trace-id";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, incomingTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedMdcValue = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedMdcValue.set(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY));
            return null;
        }).when(mockChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, mockChain);

        assertThat(capturedMdcValue.get()).isEqualTo(incomingTraceId);
    }

    @Test
    void mdcIsClearedAfterFilterExecution() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "test-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        // MDC should be cleared after filter completes
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    void blankIncomingTraceIdGeneratesNewOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String traceIdInResponse = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
        // Blank header should be treated as absent — new UUID generated
        assertThat(traceIdInResponse).isNotBlank();
        assertThat(traceIdInResponse).isNotEqualTo("   ");
    }

    @Test
    void filterChainIsContinuedAfterSettingTraceId(@Mock FilterChain mockChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, mockChain);

        verify(mockChain).doFilter(request, response);
    }

    @Test
    void mdcIsClearedEvenWhenFilterChainThrows(@Mock FilterChain mockChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "error-trace");
        MockHttpServletResponse response = new MockHttpServletResponse();

        doAnswer(invocation -> { throw new ServletException("simulated error"); })
                .when(mockChain).doFilter(any(), any());

        try {
            filter.doFilterInternal(request, response, mockChain);
        } catch (ServletException ignored) {
            // Expected
        }

        // MDC must be cleared even when the filter chain throws
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY)).isNull();
    }
}
