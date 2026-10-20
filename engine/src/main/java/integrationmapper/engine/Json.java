package integrationmapper.engine;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared JSON helpers; error bodies use one shape: {"error", "integration", "detail"}. */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String error(String error, String integration, String detail) {
        return error(error, integration, detail, List.of());
    }

    /** Same shape plus a "problems" list when there is one, e.g. every field that failed validation. */
    static String error(String error, String integration, String detail, List<String> problems) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("error", error);
        n.put("integration", integration);
        if (detail != null) {
            n.put("detail", detail);
        }
        if (!problems.isEmpty()) {
            problems.forEach(n.putArray("problems")::add);
        }
        return n.toString();
    }
}
