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
 */
public final class ScriptGenerator {

    /** Top-level keys the request script always returns (eng review OV-4). */
    static final List<String> REQUEST_PARTS = List.of("path", "query", "headers", "body");

    private ScriptGenerator() {
    }

    /** A resolved link: source and target paths, plus a literal when the source is a constant (R11). */
    private record Resolved(LinkRules.Path source, LinkRules.Path target, String constant, String label) {
    }

    public static String requestScript(List<ServiceDefinition.Link> links, PayloadDefinition input) {
        List<String> problems = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        for (ServiceDefinition.Link link : links) {
            String label = link.from() + " → " + link.to();
            LinkRules.Path target = LinkRules.Path.of(link.to());
            if (!REQUEST_PARTS.contains(target.segments().get(0)) || target.segments().size() < 2) {
                problems.add(label + ": target must be inside one of " + REQUEST_PARTS);
                continue;
            }
            var source = input.find(link.from());
            if (source.isEmpty()) {
                problems.add(label + ": " + link.from() + " is not a field of " + input.id());
                continue;
            }
            String constant = source.get().field().isConstant()
                    ? Json.MAPPER.valueToTree(source.get().field().value()).toString() : null;
            resolved.add(new Resolved(new LinkRules.Path(source.get().namePath()), target, constant, label));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (String part : REQUEST_PARTS) {
            root.put(part, new LinkedHashMap<String, Object>());
        }
        return build(root, resolved, problems, true);
    }

    public static String responseScript(List<ServiceDefinition.Link> links, PayloadDefinition output) {
        List<String> problems = new ArrayList<>();
        List<Resolved> resolved = new ArrayList<>();
        for (ServiceDefinition.Link link : links) {
            String label = link.from() + " → " + link.to();
            var target = output.find(link.to());
            if (target.isEmpty()) {
                problems.add(label + ": " + link.to() + " is not a field of " + output.id());
                continue;
            }
            resolved.add(new Resolved(LinkRules.Path.of(link.from()), new LinkRules.Path(target.get().namePath()), null, label));
        }
        return build(new LinkedHashMap<>(), resolved, problems, false);
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
    private static String build(Map<String, Object> root, List<Resolved> links, List<String> problems, boolean keepRootKeys) {
        problems.addAll(LinkRules.checkAll(links.stream().map(l -> new LinkRules.Path[] {l.source(), l.target()}).toList()));
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
