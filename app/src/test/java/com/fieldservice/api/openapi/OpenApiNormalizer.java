package com.fieldservice.api.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Normalises an OpenAPI JSON document for stable snapshot comparison.
 *
 * <p>Transformations applied:
 * <ol>
 *   <li>Strip volatile fields — replaces {@code info.version} with {@code "SNAPSHOT"}
 *       and all {@code servers[].url} entries with {@code "PLACEHOLDER"}.</li>
 *   <li>Sort all JSON object keys deterministically (recursive TreeMap ordering)
 *       so the output is stable across JVM runs, platforms, and springdoc versions.</li>
 *   <li>Pretty-print with 2-space indentation for readable diffs.</li>
 * </ol>
 *
 * <p>Only genuinely volatile fields are stripped. Schema content, response declarations,
 * and operation structure are preserved so drift is detected.
 */
final class OpenApiNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private OpenApiNormalizer() {}

    /**
     * Normalises the given OpenAPI JSON document and returns the stable canonical form.
     *
     * @param json raw OpenAPI JSON from the /api-docs endpoint
     * @return normalised, deterministically-ordered JSON string
     */
    @SuppressWarnings("unchecked")
    static String normalize(String json) {
        try {
            Map<String, Object> doc = MAPPER.readValue(json, Map.class);
            stripVolatileFields(doc);
            Object sorted = sortKeys(doc);
            return MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to normalise OpenAPI document: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void stripVolatileFields(Map<String, Object> doc) {
        // Strip version from info
        Object info = doc.get("info");
        if (info instanceof Map<?, ?> infoMap) {
            ((Map<String, Object>) infoMap).put("version", "SNAPSHOT");
        }

        // Strip server URLs
        Object servers = doc.get("servers");
        if (servers instanceof List<?> serverList) {
            for (Object server : serverList) {
                if (server instanceof Map<?, ?> serverMap) {
                    ((Map<String, Object>) serverMap).put("url", "PLACEHOLDER");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Object sortKeys(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(k.toString(), sortKeys(v)));
            return sorted;
        } else if (obj instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(sortKeys(item));
            }
            return result;
        }
        return obj;
    }
}
