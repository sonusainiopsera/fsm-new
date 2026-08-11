package com.fieldservice.aigateway.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Typed configuration for the AI gateway module.
 * Bind via {@code @EnableConfigurationProperties(AiGatewayProperties.class)}.
 */
@ConfigurationProperties("ai")
record AiGatewayProperties(
        Copilot copilot,
        Provider provider
) {

    record Copilot(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("100") int dailyCapPerUser
    ) {}

    record Provider(
            @DefaultValue("https://api.example.com/v1") String baseUrl,
            @DefaultValue("") List<String> allowedHosts,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout,
            @DefaultValue("0.00002") double costPerToken
    ) {}
}
