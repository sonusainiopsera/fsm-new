package com.fieldservice.app.config;

import com.fieldservice.app.filter.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;

/**
 * Web/MVC configuration registered exclusively under the {@code api} profile.
 *
 * <p>Beans in this class are ABSENT when the application runs with the {@code worker}
 * profile only. This enforces blast-radius separation: a scheduler failure or
 * notification-provider outage cannot affect the request-serving beans.</p>
 *
 * <p>Registered beans:</p>
 * <ul>
 *   <li>{@link TraceIdFilter} — MDC traceId propagation for every HTTP request.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("api")
public class ApiWebConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApiWebConfiguration.class);

    /**
     * Creates the {@link TraceIdFilter} bean.
     * Not a {@code @Component} — it is only created when the {@code api} profile is active.
     */
    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    /**
     * Registers the {@link TraceIdFilter} as the highest-precedence servlet filter
     * so every subsequent filter and handler sees the traceId in the MDC.
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter traceIdFilter) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(traceIdFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        log.info("Registered TraceIdFilter for api profile");
        return registration;
    }
}
