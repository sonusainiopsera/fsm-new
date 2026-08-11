package com.fieldservice.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.fieldservice")
@EntityScan(basePackages = "com.fieldservice.domain")
@EnableJpaRepositories(basePackages = "com.fieldservice.domain")
public class FieldServiceApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FieldServiceApiApplication.class, args);
    }
}
