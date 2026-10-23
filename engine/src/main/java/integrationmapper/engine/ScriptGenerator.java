package integrationmapper.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns links into a DataSonnet script. Payload-side ends of links are field ids, resolved to the fields' current
 * names (D-18). A link between two lists ({@code ...[]}) maps every item; links inside it run per item, and fields
 * from outside the list are the same for every item (D-12). Every problem is collected and reported at once, and
 * any problem stops the version from compiling, so red or broken links can't be published (R8).
 * <p>
 * A key is written only when its source key exists: a field the caller (or the external system) did not send is
 * left out instead of being sent as null, while an explicit null is passed on, so an update can still reset a field.
 * An object is left out when nothing inside it was sent. The request parts are always there.
 * <p>
 * Files (D-29) are JSON here: a blob is the object {@code {contentType, fileName, size, data}}, a link end
 * {@code <field id>.<part>} reads or writes one of its keys, {@code body} alone is the whole request body and
 * {@code $body} the whole answer. The runtime puts raw and multipart files into that shape before mapping.
 */
public final class ScriptGenerator {

    /** Top-level keys the request script always returns (eng review OV-4). */
    static final List<String> REQUEST_PARTS = List.of("path", "query", "headers", "body");

    /** The parts of a file and their types (D-29). */
    static final Map<String, String> BLOB_PARTS = Map.of("data", "text", "fileName", "text", "contentType", "text", "size", "integer");

    private ScriptGenerator() {
    }

    /**
     * A resolved link: source and target paths with their types where known, plus a literal when the source is a
     * constant (R11).
     */
    private record Resolved(LinkRules.Path source, String sourceType, LinkRules.Path target, String targetType, String constant,
            String label) {
    }

    /** The payload end of a link: a field, or one part of a file field ({@code part} set). */
    private record End(LinkRules.Path path, String type, PayloadDefinition.Field field, String part) {
    }

    public static String requestScript(List<ServiceDefinition.Link> links, PayloadDefinition input) {
        return requestScript(links, input, null);
    }

    /** {@code requestFormat}: json (null), xml, binary or multipart; the file rules depend on it. */
    public static String requestScript(List<ServiceDefinition.Link> links, PayloadDefinition input, String requestFormat) {
        List<String> problems = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        for (ServiceDefinition.Link link : links) {
            String label = link.from() + " → " + link.to();
            LinkRules.Path target = LinkRules.Path.of(link.to());
            boolean wholeBody = target.segments().equals(List.of(LinkRules.BODY));
            if (!REQUEST_PARTS.contains(target.segments().get(0)) || (target.segments().size() < 2 && !wholeBody)) {
                problems.add(label + ": target must be inside one of " + REQUEST_PARTS);
                continue;
            }
            End source = resolve(input, link.from(), label, problems);
            if (source == null) {
                continue;
            }
            String constant = source.part() == null && source.field().isConstant()
                    ? Json.MAPPER.valueToTree(source.field().value()).toString() : null;
            resolved.add(new Resolved(source.path(), source.type(), target, null, constant, label));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (String part : REQUEST_PARTS) {
            root.put(part, new LinkedHashMap<String, Object>());
        }
        return build(root, resolved, problems, true, requestFormat);
    }

    public static String responseScript(List<ServiceDefinition.Link> links, PayloadDefinition output) {
        List<String> problems = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        Map<String, List<String>> fileParts = new LinkedHashMap<>();
        for (ServiceDefinition.Link link : links) {
            String label = link.from() + " → " + link.to();
            End target = resolve(output, link.to(), label, problems);
            if (target == null) {
                continue;
            }
            if ("size".equals(target.part())) {
                problems.add(label + ": size is worked out from the data and can't be linked to");
                continue;
            }
            if (target.part() != null) {
                fileParts.computeIfAbsent(String.join(".", target.path().segments().subList(0, target.path().segments().size() - 1)),
                        k -> new ArrayList<>()).add(target.part());
            }
            LinkRules.Path source = LinkRules.Path.of(link.from());
            resolved.add(new Resolved(source, answerType(source), target.path(), target.type(), null, label));
        }
        fileParts.forEach((file, parts) -> {
            if (!parts.contains("data")) {
                problems.add(file + ": link the file's data (base64) too");
            }
        });
        return build(new LinkedHashMap<>(), resolved, problems, false, null);
    }

    /** A field id, or {@code <file field id>.<part>}; null with a problem added when it is neither. */
    private static End resolve(PayloadDefinition def, String ref, String label, List<String> problems) {
        var field = def.find(ref);
        if (field.isPresent()) {
            return new End(new LinkRules.Path(field.get().namePath()), field.get().field().type(), field.get().field(), null);
        }
        int dot = ref.lastIndexOf('.');
        var file = dot > 0 ? def.find(ref.substring(0, dot)) : java.util.Optional.<PayloadDefinition.Located>empty();
        if (file.isPresent() && "blob".equals(file.get().field().type())) {
            String part = ref.substring(dot + 1);
            if (!BLOB_PARTS.containsKey(part)) {
                problems.add(label + ": " + part + " is not a part of a file (data, fileName, contentType, size)");
                return null;
            }
            List<String> path = new ArrayList<>(file.get().namePath());
            path.add(part);
            return new End(new LinkRules.Path(path), BLOB_PARTS.get(part), file.get().field(), part);
        }
        problems.add(label + ": " + ref + " is not a field of " + def.id());
        return null;
    }

    /** The type of an answer path where it is known: the whole answer is a file, {@code $body.<part>} one of its parts. */
    private static String answerType(LinkRules.Path source) {
        List<String> segs = source.segments();
        if (!LinkRules.WHOLE_ANSWER.equals(segs.get(0))) {
            return null;
        }
        return segs.size() == 1 ? "blob" : segs.size() == 2 ? BLOB_PARTS.get(segs.get(1)) : null;
    }

    // ---- building the target tree

    /** A mapped value and the condition under which its key is written; {@code guard} null means always. */
    private record Value(String expr, String guard) {
    }

    /** A target list: filled by mapping the source list item by item. */
    private static final class ListNode {
        final LinkRules.Path source;
        final int level;
        final Map<String, Object> item = new LinkedHashMap<>();

        ListNode(LinkRules.Path source, int level) {
            this.source = source;
            this.level = level;
        }
    }

    /** {@code keepRootKeys}: the request parts are always returned, even when empty. */
    private static String build(Map<String, Object> root, List<Resolved> links, List<String> problems, boolean keepRootKeys,
            String requestFormat) {
        problems.addAll(LinkRules.checkAll(links.stream()
                .map(l -> new LinkRules.Typed(l.source(), l.sourceType(), l.target(), l.targetType())).toList(), requestFormat));
        if (problems.isEmpty()) {
            // Lists first, outer before inner, so every item link finds its list node.
            List<Resolved> ordered = new ArrayList<>(links);
            ordered.sort(Comparator.comparing((Resolved r) -> !(r.source().isList() && r.target().isList()))
                    .thenComparingInt(r -> r.target().depth()));
            for (Resolved r : ordered) {
                try {
                    place(root, r);
                } catch (IllegalArgumentException e) {
                    problems.add(r.label() + ": " + e.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return render(root, keepRootKeys).expr();
    }

    @SuppressWarnings("unchecked")
    private static void place(Map<String, Object> root, Resolved r) {
        List<String> segs = r.target().segments();
        boolean listLink = r.source().isList() && r.target().isList();
        if (segs.equals(List.of(LinkRules.BODY))) {
            // The whole body (a file, D-29): only when nothing else writes into the body.
            if (root.get(LinkRules.BODY) instanceof Map<?, ?> body && !body.isEmpty()) {
                throw new IllegalArgumentException("another link already writes to " + r.target());
            }
            root.put(LinkRules.BODY, sourceValue(r.source()));
            return;
        }
        Map<String, Object> node = root;
        int level = 0;
        for (int i = 0; i < segs.size() - 1; i++) {
            String seg = segs.get(i);
            if (seg.endsWith("[]")) {
                level++;
                Object child = node.get(name(seg));
                if (!(child instanceof ListNode list)) {
                    throw new IllegalArgumentException("the list " + seg + " is not linked");
                }
                node = list.item;
            } else {
                Object child = node.computeIfAbsent(seg, k -> new LinkedHashMap<String, Object>());
                if (!(child instanceof Map)) {
                    throw new IllegalArgumentException("runs through a field that already has a value");
                }
                node = (Map<String, Object>) child;
            }
        }
        String last = segs.get(segs.size() - 1);
        Object value = listLink ? new ListNode(r.source(), level + 1)
                : r.constant() != null ? new Value(r.constant(), null) : sourceValue(r.source());
        if (node.putIfAbsent(name(last), value) != null) {
            throw new IllegalArgumentException("another link already writes to " + r.target());
        }
    }

    private static String name(String segment) {
        return segment.endsWith("[]") ? segment.substring(0, segment.length() - 2) : segment;
    }

    // ---- expressions

    /**
     * Reads a source path. Inside a mapped list the item is bound to {@code itemN} (N = list level); LinkRules has
     * already made sure the source's lists are the ones being mapped around it, so the deepest list level of the
     * source is the item variable to start from.
     */
    static String sourceExpr(LinkRules.Path source) {
        return sourceValue(source).expr();
    }

    private static Value sourceValue(LinkRules.Path source) {
        List<String> segs = source.segments();
        int lastList = -1;
        int depth = 0;
        int limit = source.isList() ? segs.size() - 1 : segs.size();
        for (int i = 0; i < limit; i++) {
            if (segs.get(i).endsWith("[]")) {
                lastList = i;
                depth++;
            }
        }
        String base = depth == 0 ? "payload" : "item" + depth;
        List<String> rest = new ArrayList<>(segs.subList(lastList + 1, segs.size()));
        if (source.isList()) {
            rest.set(rest.size() - 1, name(rest.get(rest.size() - 1)));
        }
        return read(base, rest);
    }

    /**
     * Null-safe read, so a missing optional field gives null instead of an error; the guard says whether the source
     * key exists. An empty name comes from a bare {@code []} segment, which means the document itself is the list.
     */
    private static Value read(String base, List<String> names) {
        List<String> path = names.stream().filter(n -> !n.isEmpty()).toList();
        if (path.isEmpty()) {
            return new Value(base, null);
        }
        StringBuilder guard = new StringBuilder();
        String current = base;
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                guard.append(" && ");
            }
            guard.append("std.isObject(").append(current).append(") && std.objectHas(").append(current).append(", ")
                    .append(quote(path.get(i))).append(")");
            current = current + "[" + quote(path.get(i)) + "]";
        }
        return new Value("(if " + guard + " then " + current + " else null)", "(" + guard + ")");
    }

    /**
     * Renders one object. Each key gets the guard of its value ({@code [if guard then "key" else null]}, which Jsonnet
     * leaves out); an object's guard is "any of its keys is written". With {@code keepKeys} every key is written.
     */
    @SuppressWarnings("unchecked")
    private static Value render(Map<String, Object> tree, boolean keepKeys) {
        StringBuilder sb = new StringBuilder("{");
        List<String> guards = new ArrayList<>();
        boolean always = tree.isEmpty();
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            Value v;
            if (e.getValue() instanceof ListNode list) {
                Value array = sourceValue(list.source);
                if (list.item.isEmpty()) {
                    v = array; // list linked without item links: items are copied as they are
                } else {
                    String var = "item" + list.level;
                    v = new Value("(if std.isArray(" + array.expr() + ") then std.map(function(" + var + ") "
                            + render(list.item, false).expr() + ", " + array.expr() + ") else " + array.expr() + ")", array.guard());
                }
            } else if (e.getValue() instanceof Map) {
                v = render((Map<String, Object>) e.getValue(), false);
            } else {
                v = (Value) e.getValue();
            }
            if (keepKeys || v.guard() == null) {
                sb.append(quote(e.getKey()));
                always = true;
            } else {
                sb.append("[if ").append(v.guard()).append(" then ").append(quote(e.getKey())).append(" else null]");
                guards.add(v.guard());
            }
            sb.append(": ").append(v.expr());
        }
        String guard = always || keepKeys ? null : "(" + String.join(" || ", guards) + ")";
        return new Value(sb.append("}").toString(), guard);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
