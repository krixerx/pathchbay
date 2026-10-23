package integrationmapper.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Fill from the API later" (step 1): builds a missing Input or Output definition from the chosen operation, Input
 * from its parameters and request body, Output from its response, plus the links between each new field and the
 * API field it came from. Lists are linked level by level, as D-12 requires. Field ids follow the same pattern as
 * {@link SampleInference}.
 */
final class Prefill {

    record Result(PayloadDefinition definition, List<ServiceDefinition.Link> links, List<String> notes) {
    }

    private static final Map<String, String> PARAM_PREFIX = Map.of("path", "path", "query", "query", "header", "headers");

    private final String defId;
    private final boolean request;
    private final Set<String> ids = new HashSet<>();
    private final List<ServiceDefinition.Link> links = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Prefill(String defId, boolean request) {
        this.defId = defId;
        this.request = request;
    }

    /** Input: one field per path/query/header parameter, then the request body's fields (a body field with a parameter's name is shared). */
    static Result input(String defId, String name, Contract.Operation op) {
        Prefill p = new Prefill(defId, true);
        List<PayloadDefinition.Field> fields = new ArrayList<>();
        for (Contract.Parameter param : op.parameters()) {
            String prefix = PARAM_PREFIX.get(param.in());
            if (prefix == null) {
                p.notes.add("Parameter " + param.name() + " (" + param.in() + ") is not supported and was left out");
                continue;
            }
            PayloadDefinition.Field f = existing(fields, param.name());
            if (f == null) {
                f = new PayloadDefinition.Field(p.id(param.name()), param.name(), param.type(), param.required(), null, null, null, null);
                fields.add(f);
            }
            p.link(f.id(), prefix + "." + param.name());
        }
        PayloadDefinition.Field body = op.requestBody();
        if (body != null && "object".equals(body.type())) {
            for (PayloadDefinition.Field c : body.children()) {
                PayloadDefinition.Field shared = existing(fields, c.name());
                if (shared != null && PayloadDefinition.SIMPLE.contains(shared.type()) && shared.type().equals(c.type())) {
                    p.link(shared.id(), "body." + c.name());
                } else if (shared != null) {
                    addIfCopied(fields, p.copy(c, "body_" + c.name(), "body." + c.name(), "body_" + c.name()));
                } else {
                    addIfCopied(fields, p.copy(c, c.name(), "body." + c.name(), c.name()));
                }
            }
        } else if (body != null) {
            addIfCopied(fields, p.copy(body, "body", "body", "body"));
        }
        return new Result(new PayloadDefinition(defId, name, fields), p.links, p.notes);
    }

    /** Output: the response's fields; a list response becomes one list field {@code items}. */
    static Result output(String defId, String name, Contract.Operation op) {
        Prefill p = new Prefill(defId, false);
        List<PayloadDefinition.Field> fields = new ArrayList<>();
        PayloadDefinition.Field response = op.response();
        if (response == null) {
            p.notes.add("The contract does not describe the response, so Output was created empty. Add its fields on the"
                    + " Payload definitions page, for example from a sample of a real answer.");
        } else if ("object".equals(response.type())) {
            for (PayloadDefinition.Field c : response.children()) {
                addIfCopied(fields, p.copy(c, c.name(), c.name(), c.name()));
            }
        } else if ("list".equals(response.type())) {
            addIfCopied(fields, p.copy(response, "items", "", "items"));
        } else {
            p.notes.add("The response is a single " + response.type() + " value; Output was created empty");
        }
        return new Result(new PayloadDefinition(defId, name, fields), p.links, p.notes);
    }

    private static void addIfCopied(List<PayloadDefinition.Field> fields, PayloadDefinition.Field f) {
        if (f != null) {
            fields.add(f);
        }
    }

    /**
     * Copies one contract field under a new id and links it to {@code ext}, the API path (no leading dot; "" is the
     * root of a list response). Objects are not linked themselves, only their fields. Answers null, with a note, for
     * what a payload definition can't hold: a blob below the top level or a list of blobs (D-29).
     */
    private PayloadDefinition.Field copy(PayloadDefinition.Field f, String newName, String ext, String idPath) {
        return copy(f, newName, ext, idPath, true);
    }

    private PayloadDefinition.Field copy(PayloadDefinition.Field f, String newName, String ext, String idPath, boolean topLevel) {
        if ("blob".equals(f.type()) && !topLevel) {
            notes.add(idPath + " is a file (blob) inside an object or list; blobs can only be top-level fields, so it was left out");
            return null;
        }
        String id = id(idPath);
        switch (f.type()) {
            case "object" -> {
                List<PayloadDefinition.Field> kids = new ArrayList<>();
                for (PayloadDefinition.Field c : f.children()) {
                    addIfCopied(kids, copy(c, c.name(), join(ext, c.name()), idPath + "." + c.name(), false));
                }
                return new PayloadDefinition.Field(id, newName, "object", f.required(), null, null, kids, null);
            }
            case "list" -> {
                PayloadDefinition.Field items = f.items();
                if (items != null && "blob".equals(items.type())) {
                    notes.add(idPath + " is a list of files (blob); lists of blobs aren't supported, so it was left out");
                    return null;
                }
                String list = ext + "[]";
                link(id, list);
                PayloadDefinition.Field newItems = null;
                if (items != null && "object".equals(items.type())) {
                    List<PayloadDefinition.Field> kids = new ArrayList<>();
                    for (PayloadDefinition.Field c : items.children()) {
                        addIfCopied(kids, copy(c, c.name(), list + "." + c.name(), idPath + "." + c.name(), false));
                    }
                    newItems = new PayloadDefinition.Field(null, null, "object", false, null, null, kids, null);
                } else if (items != null && "list".equals(items.type())) {
                    notes.add(newName + " is a list of lists; its inner lists are copied but not linked");
                    newItems = items;
                } else if (items != null) {
                    newItems = new PayloadDefinition.Field(null, null, items.type(), false, items.format(), null, null, null);
                }
                return new PayloadDefinition.Field(id, newName, "list", f.required(), null, null, null, newItems);
            }
            default -> {
                link(id, ext);
                return new PayloadDefinition.Field(id, newName, f.type(), f.required(), f.format(), null, null, null);
            }
        }
    }

    private void link(String fieldId, String ext) {
        links.add(request ? new ServiceDefinition.Link(fieldId, ext) : new ServiceDefinition.Link(ext, fieldId));
    }

    private static String join(String ext, String name) {
        return ext.isEmpty() ? name : ext + "." + name;
    }

    private String id(String path) {
        String base = defId + "-" + path.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "").toLowerCase();
        String id = base;
        for (int n = 2; !ids.add(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private static PayloadDefinition.Field existing(List<PayloadDefinition.Field> fields, String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
    }
}
