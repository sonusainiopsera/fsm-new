package com.fieldservice.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
public class PasswordEncoderConfig {

    /** BCrypt cost factor — fixed at 12 per security policy; must not be lowered. */
    static final int BCRYPT_STRENGTH = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt));
    }
}
