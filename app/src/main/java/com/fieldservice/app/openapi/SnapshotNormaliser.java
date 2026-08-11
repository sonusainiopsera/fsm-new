package com.fieldservice.app.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Normalises an OpenAPI JSON document for stable snapshot comparison.
 *
 * <p>Transformations applied:
 * <ol>
 *   <li>Replace {@code info.version} with {@code {{version}}} (changes each build).</li>
 *   <li>Replace {@code servers[*].url} with {@code {{base-url}}} (environment-specific).</li>
 *   <li>Remove any top-level {@code x-generated-on} timestamp key.</li>
 *   <li>Sort all JSON object keys alphabetically (recursive) for deterministic output.</li>
 * </ol>
 *
 * <p>The normalised document is serialised as pretty-printed canonical JSON so diffs in
 * snapshot failures are human-readable.
 */
public final class SnapshotNormaliser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private SnapshotNormaliser() {}

    /**
     * Normalises {@code root} and returns the canonical JSON string.
     */
    public static String normalise(JsonNode root) throws Exception {
        JsonNode copy = root.deepCopy();
        stripVolatileFields(copy);
        JsonNode sorted = sortKeys(copy);
        return MAPPER.writeValueAsString(sorted);
    }

    private static void stripVolatileFields(JsonNode root) {
        if (!root.isObject()) {
            return;
        }
        ObjectNode obj = (ObjectNode) root;

        // Strip x-generated-on and similar timestamp extensions
        obj.remove("x-generated-on");
        obj.remove("x-generated-by");

        // Replace info.version
        JsonNode info = obj.get("info");
        if (info != null && info.isObject()) {
            ((ObjectNode) info).put("version", "{{version}}");
        }

        // Replace servers[*].url
        JsonNode servers = obj.get("servers");
        if (servers != null && servers.isArray()) {
            for (JsonNode server : servers) {
                if (server.isObject()) {
                    ((ObjectNode) server).put("url", "{{base-url}}");
                }
            }
        }
    }

    private static JsonNode sortKeys(JsonNode node) throws Exception {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            TreeMap<String, JsonNode> tree = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                tree.put(entry.getKey(), sortKeys(entry.getValue()));
            }
            tree.forEach(sorted::set);
            return sorted;
        } else if (node.isArray()) {
            ArrayNode sortedArr = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                sortedArr.add(sortKeys(element));
            }
            return sortedArr;
        }
        return node;
    }
}
