package com.fieldservice.platform.pagination;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link PageQuery} method parameters in Spring MVC controllers.
 *
 * <p>Reads {@code page}, {@code size}, {@code sort}, and {@code cursor} from the
 * HTTP query string. Size is clamped to {@link PageQuery#MAX_SIZE} by the
 * {@link PageQuery} compact constructor, so the cap is enforced regardless of
 * how the instance is created.
 */
public class PageQueryHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public PageQuery resolveArgument(MethodParameter parameter,
                                     ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest,
                                     WebDataBinderFactory binderFactory) {
        int page = parseInt(webRequest.getParameter("page"), 0);
        int size = parseInt(webRequest.getParameter("size"), PageQuery.DEFAULT_SIZE);
        String sort   = webRequest.getParameter("sort");
        String cursor = webRequest.getParameter("cursor");
        return new PageQuery(page, size, sort, cursor);
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
