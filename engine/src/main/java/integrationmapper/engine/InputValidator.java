package integrationmapper.engine;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Checks a caller's input against the Input definition before anything is sent (runtime rule "Invalid inbound
 * requests"). Constants are skipped: callers never send them. Fields not in the definition are ignored.
 */
final class InputValidator {

    private InputValidator() {
    }

    /** Problems as "path: message"; empty when the input is valid. */
    static List<String> validate(JsonNode input, PayloadDefinition def) {
        List<String> problems = new ArrayList<>();
        checkObject(input, def.fields() == null ? List.of() : def.fields(), "", problems);
        return problems;
    }

    private static void checkObject(JsonNode obj, List<PayloadDefinition.Field> fields, String at, List<String> problems) {
        for (PayloadDefinition.Field f : fields) {
            if (f.isConstant()) {
                continue;
            }
            String path = at + f.name();
            JsonNode v = obj.get(f.name());
            if (v == null || v.isNull()) {
                if (f.required()) {
                    problems.add(path + ": required");
                }
                continue;
            }
            checkValue(v, f, path, problems);
        }
    }

    private static void checkValue(JsonNode v, PayloadDefinition.Field f, String path, List<String> problems) {
        switch (f.type()) {
            case "text" -> expect(v.isTextual(), path, "must be text", problems);
            case "number" -> expect(v.isNumber(), path, "must be a number", problems);
            case "integer" -> expect(v.isIntegralNumber(), path, "must be a whole number", problems);
            case "boolean" -> expect(v.isBoolean(), path, "must be true or false", problems);
            case "date" -> expect(v.isTextual() && parses(() -> LocalDate.parse(v.asText())), path,
                    "must be a date like 2026-10-20", problems);
            case "datetime" -> expect(v.isTextual() && parses(() -> OffsetDateTime.parse(v.asText())), path,
                    "must be a date-time like 2026-10-20T10:00:00Z", problems);
            case "object" -> {
                if (expect(v.isObject(), path, "must be an object", problems)) {
                    checkObject(v, f.children(), path + ".", problems);
                }
            }
            case "list" -> {
                if (expect(v.isArray(), path, "must be a list", problems)) {
                    for (int i = 0; i < v.size(); i++) {
                        if (!v.get(i).isNull()) {
                            checkValue(v.get(i), f.items(), path + "[" + i + "]", problems);
                        }
                    }
                }
            }
            default -> problems.add(path + ": unknown type " + f.type());
        }
    }

    /**
     * GET/DELETE input arrives as text; turn each value into its defined type where it parses, so validation then
     * reports what doesn't (R1, D16).
     */
    static ObjectNode coerceQuery(ObjectNode raw, PayloadDefinition def) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (PayloadDefinition.Field f : def.fields() == null ? List.<PayloadDefinition.Field>of() : def.fields()) {
            JsonNode v = raw.get(f.name());
            if (v == null || f.isConstant()) {
                continue;
            }
            if ("list".equals(f.type())) {
                ArrayNode arr = out.putArray(f.name());
                if (v.isArray()) {
                    v.forEach(item -> arr.add(coerce(item.asText(), f.items().type())));
                } else {
                    arr.add(coerce(v.asText(), f.items().type()));
                }
            } else {
                out.set(f.name(), coerce(v.asText(), f.type()));
            }
        }
        return out;
    }

    static JsonNode coerce(String s, String type) {
        JsonNodeFactory n = JsonNodeFactory.instance;
        try {
            return switch (type) {
                case "integer" -> n.numberNode(Long.parseLong(s));
                case "number" -> n.numberNode(new java.math.BigDecimal(s));
                case "boolean" -> "true".equals(s) || "false".equals(s) ? n.booleanNode(Boolean.parseBoolean(s)) : n.textNode(s);
                default -> n.textNode(s);
            };
        } catch (NumberFormatException e) {
            return n.textNode(s); // left as text so validation names the field
        }
    }

    private static boolean expect(boolean ok, String path, String message, List<String> problems) {
        if (!ok) {
            problems.add(path + ": " + message);
        }
        return ok;
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
