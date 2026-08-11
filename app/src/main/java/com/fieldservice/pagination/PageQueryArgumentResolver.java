package com.fieldservice.pagination;

import com.fieldservice.platform.pagination.InvalidSortException;
import com.fieldservice.platform.pagination.PageQuery;
import com.fieldservice.platform.pagination.SortField;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@link PageQuery} method parameters from HTTP query parameters.
 *
 * <p>Reads {@code page}, {@code size}, {@code sort}, and {@code cursor} from the request.
 * {@code size} is clamped to {@value PageQuery#MAX_SIZE} regardless of the client-supplied value
 * — the clamping is enforced in the {@link PageQuery} record constructor, making it impossible
 * to exceed the cap from any client input path.
 *
 * <p>Unknown sort token formats are rejected with {@link InvalidSortException}, which the global
 * exception handler maps to 400. The allow-list check (field name validation) happens later in
 * {@code SpecificationPageService}, which has the per-resource context.
 */
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    @Override
    public PageQuery resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);

        int page = parseIntParam(request, "page", PageQuery.DEFAULT_PAGE);
        int size = parseIntParam(request, "size", PageQuery.DEFAULT_SIZE);
        String cursor = request != null ? request.getParameter("cursor") : null;

        List<SortField> sortFields = new ArrayList<>();
        if (request != null) {
            String[] sortParams = request.getParameterValues("sort");
            if (sortParams != null) {
                for (String token : sortParams) {
                    if (token != null && !token.isBlank()) {
                        sortFields.add(SortField.parse(token.strip()));
                    }
                }
            }
        }

        return new PageQuery(page, size, sortFields, cursor);
    }

    private static int parseIntParam(HttpServletRequest request, String name, int defaultValue) {
        if (request == null) return defaultValue;
        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
