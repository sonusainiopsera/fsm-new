package com.fieldservice.pagination;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Registers pagination argument resolvers so {@link com.fieldservice.platform.pagination.PageQuery}
 * can be injected into controller methods as a parameter.
 */
@Configuration
public class PaginationWebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryArgumentResolver());
    }
}
