package integrationmapper.engine;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

/**
 * JSON ↔ XML at the edges of a call (D-24). Mapping always works on JSON trees; XML is converted on the way out and
 * on the way in. The conventions: the root element is dropped when reading and named when writing; child elements
 * and attributes both become fields; repeated elements become a list; a list is written as repeated elements; all
 * values read from XML are text until a payload definition gives them a type.
 */
final class XmlFormat {

    private static final XmlMapper XML = new XmlMapper();
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_.-]*";
    /** Element name for the items of a list that is not inside an object (a list body). */
    static final String ITEM = "item";

    private XmlFormat() {
    }

    static void checkRoot(String root, String what) {
        if (root == null || !root.matches(NAME) || root.toLowerCase().startsWith("xml")) {
            throw new IllegalArgumentException("The " + what + " must be an XML name (letters, digits, _ - .), not "
                    + (root == null ? "empty" : "'" + root + "'"));
        }
    }

    /** Whether an answer is XML: by its Content-Type, or by its first character when it has none. */
    static boolean isXml(String contentType, String body) {
        if (contentType != null && !contentType.isBlank()) {
            return contentType.toLowerCase().contains("xml");
        }
        return body != null && body.stripLeading().startsWith("<");
    }

    static String write(JsonNode value, String root) {
        JsonNode v = value.isArray() ? JsonNodeFactory.instance.objectNode().set(ITEM, value) : value;
        try {
            return XML.writer().withRootName(root).writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not write XML: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads an XML answer into a JSON tree. XML can't tell a list of one from a single element, so every place that
     * {@code listPaths} (link sources such as {@code books[]} or {@code [].authors[]}) says is a list becomes one.
     */
    static JsonNode read(String xml, Collection<String> listPaths) {
        JsonNode root;
        try {
            root = XML.readTree(xml);
        } catch (Exception e) {
            throw new IllegalArgumentException("The answer is not valid XML: " + e.getMessage(), e);
        }
        for (String p : listPaths) {
            root = ensureList(root, LinkRules.Path.of(p).segments(), 0);
        }
        return root;
    }

    /** Every list prefix of the given link sources, shortest first, so outer lists are fixed before inner ones. */
    static List<String> listPaths(Collection<String> sources) {
        TreeSet<String> out = new TreeSet<>(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        for (String s : sources) {
            LinkRules.Path p = LinkRules.Path.of(s);
            for (int level = 1; level <= p.depth(); level++) {
                out.add(String.join(".", p.listPrefix(level).segments()));
            }
        }
        return List.copyOf(out);
    }

    private static JsonNode ensureList(JsonNode node, List<String> segs, int i) {
        if (i == segs.size() || node == null) {
            return node;
        }
        String seg = segs.get(i);
        if (seg.equals("[]")) {
            // A list answer arrives wrapped in its root, e.g. <books><book/><book/></books> reads as {"book": [...]}.
            JsonNode v = node;
            if (node.isObject() && node.size() == 1) {
                v = node.elements().next();
            }
            return eachItem(asList(v), segs, i);
        }
        if (!node.isObject()) {
            return node;
        }
        boolean list = seg.endsWith("[]");
        String name = list ? seg.substring(0, seg.length() - 2) : seg;
        JsonNode child = node.get(name);
        if (child != null) {
            ((ObjectNode) node).set(name, list ? eachItem(asList(child), segs, i) : ensureList(child, segs, i + 1));
        }
        return node;
    }

    private static ArrayNode eachItem(ArrayNode list, List<String> segs, int i) {
        for (int k = 0; k < list.size(); k++) {
            list.set(k, ensureList(list.get(k), segs, i + 1));
        }
        return list;
    }

    private static ArrayNode asList(JsonNode v) {
        if (v.isArray()) {
            return (ArrayNode) v;
        }
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        // An empty element (<books/>) is an empty list.
        if (!v.isNull() && !(v.isTextual() && v.asText().isEmpty()) && !(v.isObject() && v.isEmpty())) {
            arr.add(v);
        }
        return arr;
    }

    /**
     * Gives text read from XML the types of the payload definition: numbers and booleans where the field says so,
     * and a list where the field is a list. Text that does not parse stays text.
     */
    static JsonNode typed(JsonNode node, List<PayloadDefinition.Field> fields) {
        if (node == null || !node.isObject() || fields == null) {
            return node;
        }
        ObjectNode obj = (ObjectNode) node;
        for (PayloadDefinition.Field f : fields) {
            JsonNode v = obj.get(f.name());
            if (v != null) {
                obj.set(f.name(), typedValue(v, f));
            }
        }
        return obj;
    }

    private static JsonNode typedValue(JsonNode v, PayloadDefinition.Field f) {
        if (v.isNull()) {
            return v;
        }
        return switch (f.type()) {
            case "object" -> typed(v, f.fields());
            case "list" -> {
                ArrayNode arr = asList(v);
                for (int k = 0; k < arr.size(); k++) {
                    arr.set(k, typedValue(arr.get(k), f.items()));
                }
                yield arr;
            }
            default -> v.isTextual() ? InputValidator.coerce(v.asText(), f.type()) : v;
        };
    }
}
