package integrationmapper.engine;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns links into a DataSonnet script. Payload-side ends of links are field ids, resolved here to the fields'
 * current names (D-18). Scope: direct copies and constants (R11); lists (D-12) come with E3.
 */
public final class ScriptGenerator {

    /** Top-level keys the request script always returns (eng review OV-4). */
    static final List<String> REQUEST_PARTS = List.of("path", "query", "headers", "body");

    private ScriptGenerator() {
    }

    public static String requestScript(List<ServiceDefinition.Link> links, PayloadDefinition input) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (String part : REQUEST_PARTS) {
            tree.put(part, new LinkedHashMap<String, Object>());
        }
        for (ServiceDefinition.Link link : links) {
            List<String> target = dotted(link.to());
            if (!REQUEST_PARTS.contains(target.get(0)) || target.size() < 2) {
                throw new IllegalArgumentException("Request link target must be inside one of " + REQUEST_PARTS + ": " + link.to());
            }
            PayloadDefinition.Located source = input.find(link.from()).orElseThrow(() -> new IllegalArgumentException(
                    "Request link source " + link.from() + " is not a field of " + input.id()));
            String expr = source.field().isConstant()
                    ? Json.MAPPER.valueToTree(source.field().value()).toString()
                    : sourceExpr(source.namePath());
            put(tree, target, expr, link.to());
        }
        return render(tree);
    }

    public static String responseScript(List<ServiceDefinition.Link> links, PayloadDefinition output) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (ServiceDefinition.Link link : links) {
            PayloadDefinition.Located target = output.find(link.to()).orElseThrow(() -> new IllegalArgumentException(
                    "Response link target " + link.to() + " is not a field of " + output.id()));
            put(tree, target.namePath(), sourceExpr(dotted(link.from())), link.to());
        }
        return render(tree);
    }

    private static List<String> dotted(String path) {
        return Arrays.asList(path.split("\\."));
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> tree, List<String> path, String expr, String label) {
        Map<String, Object> node = tree;
        for (int i = 0; i < path.size() - 1; i++) {
            Object child = node.computeIfAbsent(path.get(i), k -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map)) {
                throw new IllegalArgumentException("Link target " + label + " runs through a field that already has a value");
            }
            node = (Map<String, Object>) child;
        }
        if (node.putIfAbsent(path.get(path.size() - 1), expr) != null) {
            throw new IllegalArgumentException("Two links write to " + label);
        }
    }

    /** A name path becomes a null-safe read of {@code payload}, so a missing optional field gives null instead of an error. */
    static String sourceExpr(List<String> path) {
        StringBuilder guard = new StringBuilder();
        String current = "payload";
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                guard.append(" && ");
            }
            guard.append("std.isObject(").append(current).append(") && std.objectHas(").append(current).append(", ")
                    .append(quote(path.get(i))).append(")");
            current = current + "[" + quote(path.get(i)) + "]";
        }
        return "(if " + guard + " then " + current + " else null)";
    }

    @SuppressWarnings("unchecked")
    private static String render(Map<String, Object> tree) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(quote(e.getKey())).append(": ");
            Object v = e.getValue();
            sb.append(v instanceof Map ? render((Map<String, Object>) v) : v);
        }
        return sb.append("}").toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
