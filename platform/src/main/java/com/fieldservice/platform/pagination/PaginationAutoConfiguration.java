package com.fieldservice.platform.pagination;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the {@link KeysetCursor} bean wired to the configured HMAC key. */
@Configuration
public class PaginationAutoConfiguration {

    @Bean
    public KeysetCursor keysetCursor(PaginationProperties props) {
        return new KeysetCursor(props.getCursorHmacKey());
    }
}
