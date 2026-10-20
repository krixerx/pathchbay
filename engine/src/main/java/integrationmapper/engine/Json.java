package integrationmapper.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared JSON helpers; error bodies use one shape: {"error", "integration", "detail"}. */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String error(String error, String integration, String detail) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("error", error);
        n.put("integration", integration);
        if (detail != null) {
            n.put("detail", detail);
        }
        return n.toString();
    }
}
