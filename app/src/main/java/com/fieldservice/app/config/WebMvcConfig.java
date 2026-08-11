package com.fieldservice.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Restricts HTTP message conversion to JSON only.
 *
 * <p>Removing XML converters makes XXE impossible by construction: there is no XML parser
 * to exploit, regardless of Content-Type header. An {@code application/xml} or
 * {@code text/xml} request body returns 415 Unsupported Media Type without any parsing.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(c -> !(c instanceof MappingJackson2HttpMessageConverter));
        if (converters.isEmpty()) {
            converters.add(new MappingJackson2HttpMessageConverter());
        }
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // Ensure no XML converters sneak in via auto-configuration
        converters.removeIf(c -> {
            List<MediaType> supported = c.getSupportedMediaTypes();
            return supported.stream().anyMatch(m ->
                    m.getType().equals("application") && m.getSubtype().contains("xml")
                    || m.getType().equals("text") && m.getSubtype().equals("xml"));
        });
    }
}
