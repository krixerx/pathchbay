package integrationmapper.engine;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Guesses a payload definition from a pasted JSON sample (D-13). ISO dates become date/date-time; values that say
 * nothing about their type (null, empty list) or lists with mixed item types are returned as "guesses" for the user
 * to resolve before saving. Field ids are generated from the definition id and the field path.
 */
final class SampleInference {

    /** The guessed definition plus the paths that need a decision ("pick a type"). */
    record Result(PayloadDefinition definition, List<String> guesses) {
    }

    private SampleInference() {
    }

    static Result infer(String id, String name, JsonNode sample) {
        if (sample == null || !sample.isObject()) {
            throw new IllegalArgumentException("Paste a JSON object, for example {\"title\": \"Book\"}");
        }
        List<String> guesses = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<PayloadDefinition.Field> fields = fields(sample, id, "", guesses, ids);
        return new Result(new PayloadDefinition(id, name, fields), guesses);
    }

    private static List<PayloadDefinition.Field> fields(JsonNode obj, String defId, String at, List<String> guesses, Set<String> ids) {
        List<PayloadDefinition.Field> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : obj.properties()) {
            out.add(field(e.getKey(), e.getValue(), defId, at + e.getKey(), guesses, ids));
        }
        return out;
    }

    private static PayloadDefinition.Field field(String name, JsonNode v, String defId, String path, List<String> guesses, Set<String> ids) {
        String fid = uniqueId(defId + "-" + path.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(), ids);
        if (v.isObject()) {
            return new PayloadDefinition.Field(fid, name, "object", false, null, null, fields(v, defId, path + ".", guesses, ids), null);
        }
        if (v.isArray()) {
            PayloadDefinition.Field items = items(v, defId, path, guesses, ids);
            return new PayloadDefinition.Field(fid, name, "list", false, null, null, null, items);
        }
        String type = simpleType(v);
        if (type == null) {
            guesses.add(path + ": the sample value is null; pick a type");
            type = "text";
        }
        return new PayloadDefinition.Field(fid, name, type, false, null, null, null, null);
    }

    private static PayloadDefinition.Field items(JsonNode arr, String defId, String path, List<String> guesses, Set<String> ids) {
        if (arr.isEmpty()) {
            guesses.add(path + ": the sample list is empty; pick the item type");
            return new PayloadDefinition.Field(null, null, "text", false, null, null, null, null);
        }
        JsonNode first = arr.get(0);
        if (first.isObject()) {
            // Fields of all items together, so a field present only in a later item is not lost.
            com.fasterxml.jackson.databind.node.ObjectNode merged = Json.MAPPER.createObjectNode();
            arr.forEach(item -> {
                if (item.isObject()) {
                    item.properties().forEach(p -> {
                        if (!merged.has(p.getKey()) || merged.get(p.getKey()).isNull()) {
                            merged.set(p.getKey(), p.getValue());
                        }
                    });
                }
            });
            return new PayloadDefinition.Field(null, null, "object", false, null, null, fields(merged, defId, path + "[].", guesses, ids), null);
        }
        Set<String> types = new HashSet<>();
        arr.forEach(item -> types.add(item.isNull() ? "null" : item.isContainerNode() ? "nested" : simpleType(item)));
        types.remove("null");
        if (types.size() != 1 || types.contains("nested")) {
            guesses.add(path + ": the sample list mixes item types; pick one");
            return new PayloadDefinition.Field(null, null, "text", false, null, null, null, null);
        }
        return new PayloadDefinition.Field(null, null, types.iterator().next(), false, null, null, null, null);
    }

    private static String simpleType(JsonNode v) {
        if (v.isBoolean()) {
            return "boolean";
        }
        if (v.isIntegralNumber()) {
            return "integer";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isTextual()) {
            String s = v.asText();
            if (parses(() -> LocalDate.parse(s))) {
                return "date";
            }
            if (parses(() -> OffsetDateTime.parse(s))) {
                return "datetime";
            }
            return "text";
        }
        return null;
    }

    private static boolean parses(Runnable r) {
        try {
            r.run();
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String uniqueId(String base, Set<String> ids) {
        String id = base;
        for (int n = 2; !ids.add(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }
}
