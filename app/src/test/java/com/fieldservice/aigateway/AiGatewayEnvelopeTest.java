package com.fieldservice.aigateway;

import com.fieldservice.aigateway.api.AiCapExceededException;
import com.fieldservice.aigateway.api.AiUnavailableException;
import com.fieldservice.api.GlobalExceptionHandler;
import com.fieldservice.platform.api.ErrorEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the HTTP response envelope shape for AI gateway exceptions end-to-end
 * through {@link GlobalExceptionHandler}, using standaloneSetup (no application context needed).
 */
@DisplayName("AI gateway HTTP envelope shape tests")
class AiGatewayEnvelopeTest {

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders
                .standaloneSetup(new AiTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("AiUnavailableException → 503 with AI_PROVIDER_UNAVAILABLE envelope")
    void aiUnavailable_produces503() throws Exception {
        mvc.perform(get("/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.AI_PROVIDER_UNAVAILABLE))
                .andExpect(jsonPath("$.message").value(
                        "AI assistance is temporarily unavailable. You can continue without it."))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("503 body leaks no provider name, stack trace, or prompt content")
    void aiUnavailable_bodyLeaksNoInternalDetail() throws Exception {
        mvc.perform(get("/test/unavailable"))
                .andExpect(jsonPath("$.message").value(not(containsString("Exception"))))
                .andExpect(jsonPath("$.message").value(not(containsString("stack"))))
                .andExpect(jsonPath("$.message").value(not(containsString("OpenAI"))))
                .andExpect(jsonPath("$.message").value(not(containsString("Anthropic"))))
                .andExpect(jsonPath("$.message").value(not(containsString("cause"))))
                .andExpect(jsonPath("$.message").value(not(containsString("prompt"))));
    }

    @Test
    @DisplayName("AiCapExceededException → 429 with AI_DAILY_LIMIT_REACHED and Retry-After")
    void aiCapExceeded_produces429() throws Exception {
        mvc.perform(get("/test/cap-exceeded"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorEnvelope.Code.AI_DAILY_LIMIT_REACHED))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("429 Retry-After header matches retryAfterSeconds on exception")
    void aiCapExceeded_retryAfterMatchesException() throws Exception {
        mvc.perform(get("/test/cap-exceeded-7200"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7200"));
    }

    @RestController
    static class AiTestController {

        @GetMapping("/test/unavailable")
        void triggerUnavailable() {
            throw new AiUnavailableException("provider timed out after 10s");
        }

        @GetMapping("/test/cap-exceeded")
        void triggerCapExceeded() {
            throw new AiCapExceededException("user1", 3600L);
        }

        @GetMapping("/test/cap-exceeded-7200")
        void triggerCapExceeded7200() {
            throw new AiCapExceededException("user1", 7200L);
        }
    }
}
