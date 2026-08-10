package com.fieldservice.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstrap entry point for the Field Service Platform.
 *
 * <p>A single fat jar is deployable as two distinct roles by Spring profile selection:</p>
 * <ul>
 *   <li>{@code api} — starts the HTTP server, registers web/MVC beans and
 *       controller advice. No schedulers or outbox pollers.</li>
 *   <li>{@code worker} — registers schedulers and outbox pollers; does NOT start
 *       an HTTP connector for business endpoints (management port only).</li>
 * </ul>
 *
 * <p>Both profiles may be active simultaneously for local development without
 * duplicate bean registration.</p>
 *
 * <p>Virtual threads are enabled via {@code spring.threads.virtual.enabled=true}
 * in {@code application.yml}.</p>
 */
@SpringBootApplication(scanBasePackages = "com.fieldservice")
public class FieldServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FieldServiceApplication.class, args);
    }
}
