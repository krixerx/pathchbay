package integrationmapper.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns links into a DataSonnet script. Skeleton scope: direct copies between dotted paths;
 * lists (D-12) and constants (R11) come later.
 */
public final class ScriptGenerator {

    /** Top-level keys the request script always returns (eng review OV-4). */
    static final List<String> REQUEST_PARTS = List.of("path", "query", "headers", "body");

    private ScriptGenerator() {
    }

    public static String requestScript(List<ServiceDefinition.Link> links) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (String part : REQUEST_PARTS) {
            tree.put(part, new LinkedHashMap<String, Object>());
        }
        for (ServiceDefinition.Link link : links) {
            String root = link.to().split("\\.", 2)[0];
            if (!REQUEST_PARTS.contains(root)) {
                throw new IllegalArgumentException("Request link target must start with one of " + REQUEST_PARTS + ": " + link.to());
            }
            put(tree, link.to(), sourceExpr(link.from()));
        }
        return render(tree);
    }

    public static String responseScript(List<ServiceDefinition.Link> links) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (ServiceDefinition.Link link : links) {
            put(tree, link.to(), sourceExpr(link.from()));
        }
        return render(tree);
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> tree, String dottedPath, String expr) {
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> node = tree;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
            if (!(child instanceof Map)) {
                throw new IllegalArgumentException("Link target " + dottedPath + " runs through a field that already has a value");
            }
            node = (Map<String, Object>) child;
        }
        if (node.putIfAbsent(parts[parts.length - 1], expr) != null) {
            throw new IllegalArgumentException("Two links write to " + dottedPath);
        }
    }

    /** {@code a.b} becomes a null-safe read of {@code payload.a.b}, so a missing optional field gives null instead of an error. */
    static String sourceExpr(String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        StringBuilder guard = new StringBuilder();
        String current = "payload";
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                guard.append(" && ");
            }
            guard.append("std.isObject(").append(current).append(") && std.objectHas(").append(current).append(", ")
                    .append(quote(parts[i])).append(")");
            current = current + "[" + quote(parts[i]) + "]";
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
