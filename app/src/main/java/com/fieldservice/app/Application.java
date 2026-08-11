package com.fieldservice.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Field Service API application.
 *
 * <p>Scans all sub-packages of {@code com.fieldservice} so that domain modules
 * ({@code workorder}, {@code site}, {@code customer}, etc.) are discovered automatically
 * without separate module descriptors.
 *
 * <p>Two Spring profiles drive deployable selection:
 * <ul>
 *   <li>{@code api}    — serves synchronous REST endpoints (default)</li>
 *   <li>{@code worker} — runs async background processors (outbox poller, SLA evaluator)</li>
 * </ul>
 * Both profiles share this single entry point and the same container image; profile
 * activation is determined by the {@code SPRING_PROFILES_ACTIVE} environment variable.
 */
@SpringBootApplication(scanBasePackages = "com.fieldservice")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
