package com.fieldservice.app.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SnapshotNormaliser} — volatile-field stripping and
 * deterministic key ordering, with no Spring context.
 */
class NormalisationUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void version_is_replaced_with_placeholder() throws Exception {
        String json = """
                {"openapi":"3.0.1","info":{"title":"Test","version":"1.2.3-SNAPSHOT"}}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        JsonNode root = mapper.readTree(normalised);
        assertThat(root.at("/info/version").asText()).isEqualTo("{{version}}");
    }

    @Test
    void server_url_is_replaced_with_placeholder() throws Exception {
        String json = """
                {"openapi":"3.0.1","servers":[{"url":"https://api.prod.example.com","description":"Prod"}]}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        JsonNode root = mapper.readTree(normalised);
        assertThat(root.at("/servers/0/url").asText()).isEqualTo("{{base-url}}");
    }

    @Test
    void x_generated_on_timestamp_is_removed() throws Exception {
        String json = """
                {"openapi":"3.0.1","x-generated-on":"2024-01-15T10:00:00Z","info":{"title":"T"}}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        JsonNode root = mapper.readTree(normalised);
        assertThat(root.has("x-generated-on")).isFalse();
    }

    @Test
    void object_keys_are_sorted_alphabetically() throws Exception {
        String json = """
                {"z":"last","a":"first","m":"middle"}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        // Keys in output should be: a, m, z
        int aPos = normalised.indexOf("\"a\"");
        int mPos = normalised.indexOf("\"m\"");
        int zPos = normalised.indexOf("\"z\"");
        assertThat(aPos).isLessThan(mPos).isLessThan(zPos);
    }

    @Test
    void nested_object_keys_are_sorted() throws Exception {
        String json = """
                {"outer":{"z":"last","a":"first"}}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        int aPos = normalised.indexOf("\"a\"");
        int zPos = normalised.indexOf("\"z\"");
        assertThat(aPos).isLessThan(zPos);
    }

    @Test
    void normalisation_is_idempotent() throws Exception {
        String json = """
                {"openapi":"3.0.1","info":{"title":"API","version":"1.0.0"},
                 "servers":[{"url":"http://example.com"}]}
                """;
        String first  = SnapshotNormaliser.normalise(mapper.readTree(json));
        String second = SnapshotNormaliser.normalise(mapper.readTree(first));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void empty_servers_array_is_handled() throws Exception {
        String json = """
                {"openapi":"3.0.1","servers":[]}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        JsonNode root = mapper.readTree(normalised);
        assertThat(root.at("/servers").isArray()).isTrue();
        assertThat(root.at("/servers").size()).isZero();
    }

    @Test
    void null_values_are_preserved() throws Exception {
        String json = """
                {"openapi":"3.0.1","info":{"title":"API","contact":null}}
                """;
        String normalised = SnapshotNormaliser.normalise(mapper.readTree(json));
        JsonNode root = mapper.readTree(normalised);
        assertThat(root.at("/info/contact").isNull()).isTrue();
    }

    @Test
    void deterministic_across_multiple_calls() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("z", 3);
        node.put("a", 1);
        node.put("m", 2);
        String first  = SnapshotNormaliser.normalise(node);
        String second = SnapshotNormaliser.normalise(node);
        assertThat(first).isEqualTo(second);
    }
}
