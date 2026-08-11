package com.fieldservice.platform.pagination;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers {@link PageQueryHandlerMethodArgumentResolver} so that
 * {@link PageQuery} can be declared as a method parameter in any controller.
 */
@Configuration
public class PaginationWebMvcConfigurer implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryHandlerMethodArgumentResolver());
    }
}
