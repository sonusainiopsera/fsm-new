package com.fieldservice.aiaudit.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(AiAuditProperties.class)
@EnableScheduling
class AiAuditAutoConfiguration {
    // Wires AiAuditProperties into the context and enables @Scheduled on AiInteractionPurgeJob.
}
