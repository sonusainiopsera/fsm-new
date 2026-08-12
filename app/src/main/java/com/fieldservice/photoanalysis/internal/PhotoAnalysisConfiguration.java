package com.fieldservice.photoanalysis.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the photo analysis module.
 * Activates component scanning and binds {@link PhotoAnalysisProperties}.
 */
@Configuration
@EnableConfigurationProperties(PhotoAnalysisProperties.class)
@ComponentScan(basePackages = {
        "com.fieldservice.photoanalysis.internal",
        "com.fieldservice.photoanalysis.api"
})
public class PhotoAnalysisConfiguration {
}
