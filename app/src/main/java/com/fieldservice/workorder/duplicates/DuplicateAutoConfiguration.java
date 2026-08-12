package com.fieldservice.workorder.duplicates;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DuplicateProperties.class)
public class DuplicateAutoConfiguration {}
