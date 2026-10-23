package integrationmapper.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An example response built from the contract's response tree, so the Test drawer can run the response mapping
 * without calling the real system (test panel rule: mock by default). Lists get two items so per-item mapping shows.
 */
final class MockGenerator {

    private static final JsonNodeFactory N = JsonNodeFactory.instance;

    private MockGenerator() {
    }

    static JsonNode example(PayloadDefinition.Field shape) {
        return shape == null ? N.objectNode() : value(shape, shape.name() == null ? "value" : shape.name(), 1);
    }

    private static JsonNode value(PayloadDefinition.Field f, String name, int n) {
        switch (f.type()) {
            case "object" -> {
                ObjectNode o = N.objectNode();
                for (PayloadDefinition.Field c : f.children()) {
                    o.set(c.name(), value(c, c.name(), n));
                }
                return o;
            }
            case "list" -> {
                ArrayNode a = N.arrayNode();
                if (f.items() != null) {
                    a.add(value(f.items(), name, 1));
                    a.add(value(f.items(), name, 2));
                }
                return a;
            }
            case "integer" -> {
                return N.numberNode(n);
            }
            case "number" -> {
                return N.numberNode(n + 0.5);
            }
            case "boolean" -> {
                return N.booleanNode(true);
            }
            case "date" -> {
                return N.textNode("2026-01-0" + n);
            }
            case "datetime" -> {
                return N.textNode("2026-01-0" + n + "T10:00:00Z");
            }
            case "blob" -> {
                // A small file, "example" as base64 (D-29).
                return N.objectNode().put("fileName", name + ".txt").put("contentType", "text/plain").put("size", 7)
                        .put("data", "ZXhhbXBsZQ==");
            }
            default -> {
                return N.textNode(name + " " + n);
            }
        }
    }
}
