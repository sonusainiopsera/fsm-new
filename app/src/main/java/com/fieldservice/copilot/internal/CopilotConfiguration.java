package com.fieldservice.copilot.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CopilotGroundingProperties.class)
class CopilotConfiguration {
}
