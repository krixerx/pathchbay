package integrationmapper.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A reusable payload definition from {@code config/entities/<id>.json} (D-8, D-13): the shape of what callers send
 * (Input) or get back (Output). Fields have stable ids so links survive renames (D-18). A field with a
 * {@code value} is a constant (R11): callers never send it and it can be linked as a source.
 */
public record PayloadDefinition(String id, String name, List<Field> fields) {

    /** Types from D-4, as stored in files. */
    public static final Set<String> TYPES = Set.of("text", "number", "integer", "boolean", "date", "datetime", "object", "list");
    static final Set<String> SIMPLE = Set.of("text", "number", "integer", "boolean", "date", "datetime");

    /**
     * @param fields children of an {@code object}
     * @param items  item shape of a {@code list} (its id and name are not used)
     */
    public record Field(String id, String name, String type, boolean required, String format, JsonNode value,
            List<Field> fields, Field items) {

        boolean isConstant() {
            return value != null && !value.isNull();
        }

        List<Field> children() {
            return fields == null ? List.of() : fields;
        }
    }

    /** Where a field sits: its current name path and the field itself. */
    public record Located(List<String> namePath, Field field) {
    }

    /** Structural checks run when a definition is loaded; throws with every problem listed. */
    public void check() {
        List<String> problems = new ArrayList<>();
        if (id == null || id.isBlank()) {
            problems.add("definition has no id");
        }
        checkFields(fields == null ? List.of() : fields, "", new HashSet<>(), problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Payload definition " + id + ": " + String.join("; ", problems));
        }
    }

    private static void checkFields(List<Field> fields, String at, Set<String> ids, List<String> problems) {
        Set<String> names = new HashSet<>();
        for (Field f : fields) {
            String where = at + (f.name() == null ? "?" : f.name());
            if (f.id() == null || f.id().isBlank()) {
                problems.add(where + ": field has no id");
            } else if (!ids.add(f.id())) {
                problems.add(where + ": field id " + f.id() + " is used twice");
            }
            if (f.name() == null || f.name().isBlank()) {
                problems.add(where + ": field has no name");
            } else if (!names.add(f.name())) {
                problems.add(where + ": two fields named " + f.name());
            }
            if (!TYPES.contains(f.type())) {
                problems.add(where + ": unknown type " + f.type() + " (use one of " + TYPES + ")");
                continue;
            }
            if (f.isConstant() && !SIMPLE.contains(f.type())) {
                problems.add(where + ": only simple fields can be constants");
            }
            if ("object".equals(f.type())) {
                checkFields(f.children(), where + ".", ids, problems);
            }
            if ("list".equals(f.type())) {
                if (f.items() == null || !TYPES.contains(f.items().type())) {
                    problems.add(where + ": list has no valid item type");
                } else if ("object".equals(f.items().type())) {
                    checkFields(f.items().children(), where + "[].", ids, problems);
                }
            }
        }
    }

    /** Finds a field by id anywhere in the definition, except inside lists (list mapping comes with E3). */
    public Optional<Located> find(String fieldId) {
        return find(fields == null ? List.of() : fields, fieldId, List.of());
    }

    private static Optional<Located> find(List<Field> fields, String fieldId, List<String> prefix) {
        for (Field f : fields) {
            List<String> path = new ArrayList<>(prefix);
            path.add(f.name());
            if (fieldId.equals(f.id())) {
                return Optional.of(new Located(List.copyOf(path), f));
            }
            if ("object".equals(f.type())) {
                Optional<Located> inner = find(f.children(), fieldId, path);
                if (inner.isPresent()) {
                    return inner;
                }
            }
        }
        return Optional.empty();
    }

    /** True when every field a caller sends is simple, so the definition can travel as query parameters (R1, D16). */
    public boolean isFlat() {
        for (Field f : fields == null ? List.<Field>of() : fields) {
            boolean simpleList = "list".equals(f.type()) && f.items() != null && SIMPLE.contains(f.items().type());
            if (!f.isConstant() && !SIMPLE.contains(f.type()) && !simpleList) {
                return false;
            }
        }
        return true;
    }
}
