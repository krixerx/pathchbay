package integrationmapper.engine.mapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import integrationmapper.engine.model.Operation;
import integrationmapper.engine.model.PayloadDefinition;
import integrationmapper.engine.model.RestOperation;
import integrationmapper.engine.model.ServiceDefinition;

/**
 * The shape rule of {@link LinkRules#checkShapes} with the types of both ends: payload fields from their definition,
 * external paths from the contract operation the draft was built from. A published version is compiled without its
 * contract (it is self-contained), so this runs when a draft is published, where the contract is at hand. An external
 * path the contract doesn't describe (a free-form object, a removed field) has no type and is not checked here.
 */
public final class ContractShapes {

    private ContractShapes() {
    }

    /** "from → to: message" for each link that puts a single value into an object or an object into a single value. */
    public static List<String> problems(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output, Operation op) {
        List<String> problems = new ArrayList<>();
        if (op == null) {
            return problems;
        }
        for (ServiceDefinition.Link l : def.request().links()) {
            add(problems, l, payloadType(input, l.from()), requestType(op, l.to()));
        }
        for (ServiceDefinition.Link l : def.response().links()) {
            add(problems, l, responseType(op, l.from()), payloadType(output, l.to()));
        }
        return problems;
    }

    private static void add(List<String> problems, ServiceDefinition.Link l, String sourceType, String targetType) {
        LinkRules.checkShapes(sourceType, targetType).ifPresent(r -> problems.add(l.from() + " → " + l.to() + ": " + r.message));
    }

    /** A payload field's type by id, or a file part's ({@code <file id>.<part>}); null when unknown. */
    static String payloadType(PayloadDefinition def, String ref) {
        if (def == null) {
            return null;
        }
        var field = def.find(ref);
        if (field.isPresent()) {
            return field.get().field().type();
        }
        int dot = ref.lastIndexOf('.');
        boolean filePart = dot > 0 && def.find(ref.substring(0, dot)).filter(l -> "blob".equals(l.field().type())).isPresent();
        return filePart ? ScriptGenerator.BLOB_PARTS.get(ref.substring(dot + 1)) : null;
    }

    /** The type of a request path (path, query or header parameter, or a place in the body); null when unknown. */
    static String requestType(Operation op, String path) {
        List<String> segs = Arrays.asList(path.split("\\."));
        String part = segs.get(0);
        if (segs.size() == 2 && op instanceof RestOperation rest && List.of("path", "query", "headers").contains(part)) {
            String in = "headers".equals(part) ? "header" : part;
            return rest.parameters() == null ? null : rest.parameters().stream()
                    .filter(p -> in.equals(p.in()) && p.name().equalsIgnoreCase(segs.get(1)))
                    .map(RestOperation.Parameter::type).findFirst().orElse(null);
        }
        return "body".equals(part) && segs.size() > 1 ? typeAt(op.requestBody(), segs.subList(1, segs.size())) : null;
    }

    /** The type of a path in the answer; null for the whole answer ($body, a file) and when unknown. */
    static String responseType(Operation op, String path) {
        return path.startsWith(LinkRules.WHOLE_ANSWER) ? null : typeAt(op.response(), Arrays.asList(path.split("\\.")));
    }

    /**
     * Walks a contract field tree by name; a segment ending in {@code []} is a list, and the segments after it are
     * inside its items. A bare {@code []} is the root itself when the whole answer is a list.
     */
    static String typeAt(PayloadDefinition.Field root, List<String> segs) {
        PayloadDefinition.Field f = root;
        for (String seg : segs) {
            if (f == null) {
                return null;
            }
            boolean list = seg.endsWith("[]");
            String name = list ? seg.substring(0, seg.length() - 2) : seg;
            PayloadDefinition.Field child;
            if (name.isEmpty()) {
                child = f;
            } else {
                PayloadDefinition.Field container = "list".equals(f.type()) ? f.items() : f;
                child = container == null ? null
                        : container.children().stream().filter(c -> name.equals(c.name())).findFirst().orElse(null);
            }
            if (child == null || list && !"list".equals(child.type())) {
                return null;
            }
            f = child;
        }
        return segs.get(segs.size() - 1).endsWith("[]") ? "list" : f.type();
    }
}
