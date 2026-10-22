package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.Test;

/** D-23: payload definition fields as OpenAPI schemas. */
class OpenApiSchemaTest {

    private static PayloadDefinition.Field field(String name, String type, String format) {
        return new PayloadDefinition.Field(name, name, type, false, format, null, null, null);
    }

    @Test
    void typesNestingAndFormats() {
        PayloadDefinition.Field city = new PayloadDefinition.Field("c", "city", "text", true, null, null, null, null);
        PayloadDefinition.Field item = new PayloadDefinition.Field(null, null, "object", false, null, null, List.of(city), null);
        PayloadDefinition.Field addresses = new PayloadDefinition.Field("a", "addresses", "list", false, null, null, null, item);
        assertThat(OpenApiController.schema(addresses).toString())
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}");
        assertThat(OpenApiController.schema(field("born", "date", null)).toString()).isEqualTo("{\"type\":\"string\",\"format\":\"date\"}");
        // A pattern of its own is not the standard date format, so it is described instead.
        assertThat(OpenApiController.schema(field("born", "date", "dd.MM.yyyy")).toString())
                .isEqualTo("{\"type\":\"string\",\"description\":\"Format: dd.MM.yyyy\"}");
        assertThat(OpenApiController.schema(field("n", "integer", null)).toString()).isEqualTo("{\"type\":\"integer\"}");
        PayloadDefinition.Field constant = new PayloadDefinition.Field("k", "kind", "text", false, null, new TextNode("book"), null, null);
        assertThat(OpenApiController.schema(constant).toString()).isEqualTo("{\"type\":\"string\",\"example\":\"book\"}");
    }
}
