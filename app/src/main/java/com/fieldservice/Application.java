package com.fieldservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Field Service Operations Platform — unified entry point.
 *
 * <p>Run with profile {@code api} for the HTTP API deployable:
 * {@code java -jar app.jar --spring.profiles.active=api}
 * <p>Run with profile {@code worker} for the async worker deployable:
 * {@code java -jar app.jar --spring.profiles.active=worker}
 *
 * <p>Both profiles use the same artifact (single container image); only the Spring profile
 * differs, isolating the two workloads as per the architectural decision to use two deployables
 * from one artifact.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
