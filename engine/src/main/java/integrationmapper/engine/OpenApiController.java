package integrationmapper.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * OpenAPI 3.0 documents for the live integrations (D-23, spec "the engine publishes an OpenAPI document for every
 * active integration at /api/openapi.json"), built from each live version's own Input and Output. Readable without
 * the inbound key: they describe shapes, never data or secrets.
 */
@RestController
public class OpenApiController {

    private static final Set<String> QUERY_METHODS = Set.of("GET", "DELETE");
    private static final Map<String, String> ERRORS = Map.of(
            "400", "The input does not match the Input definition (bad-request)",
            "500", "The mapping failed (mapping-error)",
            "502", "The external system could not be reached or failed (remote-unreachable, remote-failure)",
            "503", "A new version is being switched in; try again in a moment",
            "504", "The external system did not answer in time (remote-timeout)");

    private final IntegrationRegistry registry;
    private final boolean keyRequired;

    public OpenApiController(IntegrationRegistry registry, @Value("${INBOUND_API_KEY:}") String key) {
        this.registry = registry;
        this.keyRequired = !key.isBlank();
    }

    @GetMapping("/api/openapi.json")
    public ResponseEntity<String> all() {
        return json(document("Patchbay", "live", registry.active().values().stream().toList()));
    }

    @GetMapping("/api/openapi/{slug}.json")
    public ResponseEntity<String> one(@PathVariable String slug) {
        CompiledIntegration ci = registry.active().get(slug);
        if (ci == null) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(Json.error("not-found", slug, "No live version of integration " + slug));
        }
        return json(document(slug, ci.definition().version(), List.of(ci)));
    }

    ObjectNode document(String title, String version, List<CompiledIntegration> integrations) {
        ObjectNode doc = Json.MAPPER.createObjectNode();
        doc.put("openapi", "3.0.3");
        doc.putObject("info").put("title", title).put("version", version);
        doc.putArray("servers").addObject().put("url", ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
        ObjectNode paths = doc.putObject("paths");
        for (CompiledIntegration ci : integrations) {
            paths.putObject(IntegrationGate.INTEGRATIONS + ci.definition().id())
                    .set(ci.definition().target().method().toLowerCase(), operation(ci));
        }
        ObjectNode components = doc.putObject("components");
        ObjectNode error = components.putObject("schemas").putObject("Error").put("type", "object");
        ObjectNode errorProps = error.putObject("properties");
        for (String p : List.of("error", "integration", "detail")) {
            errorProps.putObject(p).put("type", "string");
        }
        errorProps.putObject("problems").put("type", "array").putObject("items").put("type", "string");
        if (keyRequired) {
            components.putObject("securitySchemes").putObject("apiKey")
                    .put("type", "apiKey").put("in", "header").put("name", InboundKeyFilter.HEADER);
            doc.putArray("security").addObject().putArray("apiKey");
        }
        return doc;
    }

    private ObjectNode operation(CompiledIntegration ci) {
        ServiceDefinition def = ci.definition();
        ObjectNode op = Json.MAPPER.createObjectNode();
        op.put("operationId", def.id());
        op.put("summary", def.id());
        op.put("description", "Live version " + def.version() + ". Calls " + def.target().method() + " "
                + def.target().path() + " of the external system.");
        List<PayloadDefinition.Field> inputFields = callerFields(ci.input().fields());
        if (QUERY_METHODS.contains(def.target().method())) {
            ArrayNode params = op.putArray("parameters");
            for (PayloadDefinition.Field f : inputFields) {
                ObjectNode p = params.addObject().put("name", f.name()).put("in", "query").put("required", f.required());
                p.set("schema", schema(f));
            }
        } else {
            op.putObject("requestBody").put("required", true).putObject("content").putObject("application/json")
                    .set("schema", object(inputFields));
        }
        ObjectNode responses = op.putObject("responses");
        ObjectNode outputSchema = object(ci.output().fields() == null ? List.of() : ci.output().fields());
        if (def.wire().outputXml()) {
            outputSchema.putObject("xml").put("name", def.wire().outputRoot());
        }
        responses.putObject("200").put("description", "The Output").putObject("content").putObject(ci.outputContentType())
                .set("schema", outputSchema);
        if (keyRequired) {
            responses.set("401", errorResponse("The X-API-Key header is missing or does not match"));
        }
        ERRORS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> responses.set(e.getKey(), errorResponse(e.getValue())));
        responses.putObject("default").put("description",
                "An error status of the external system, passed on with its own body (header X-Integration-Error: remote)");
        return op;
    }

    /** Callers never send constants (R11). */
    private static List<PayloadDefinition.Field> callerFields(List<PayloadDefinition.Field> fields) {
        return fields == null ? List.of() : fields.stream().filter(f -> !f.isConstant()).toList();
    }

    private static ObjectNode errorResponse(String description) {
        ObjectNode r = Json.MAPPER.createObjectNode().put("description", description);
        r.putObject("content").putObject("application/json").putObject("schema").put("$ref", "#/components/schemas/Error");
        return r;
    }

    private static ObjectNode object(List<PayloadDefinition.Field> fields) {
        ObjectNode s = Json.MAPPER.createObjectNode().put("type", "object");
        ObjectNode props = s.putObject("properties");
        ArrayNode required = Json.MAPPER.createArrayNode();
        for (PayloadDefinition.Field f : fields) {
            props.set(f.name(), schema(f));
            if (f.required() && !f.isConstant()) {
                required.add(f.name());
            }
        }
        if (!required.isEmpty()) {
            s.set("required", required);
        }
        return s;
    }

    /** D-4 types as JSON Schema; a custom date or text format is described, not put in {@code format}. */
    static ObjectNode schema(PayloadDefinition.Field f) {
        if ("object".equals(f.type())) {
            return object(f.children());
        }
        ObjectNode s = Json.MAPPER.createObjectNode();
        boolean custom = f.format() != null && !f.format().isBlank();
        switch (f.type()) {
            case "number", "integer", "boolean" -> s.put("type", f.type());
            case "date", "datetime" -> {
                s.put("type", "string");
                if (!custom) {
                    s.put("format", "date".equals(f.type()) ? "date" : "date-time");
                }
            }
            case "list" -> {
                s.put("type", "array");
                s.set("items", f.items() == null ? Json.MAPPER.createObjectNode() : schema(f.items()));
            }
            default -> s.put("type", "string");
        }
        if (custom) {
            s.put("description", "Format: " + f.format());
        }
        if (f.isConstant()) {
            s.set("example", f.value());
        }
        return s;
    }

    private static ResponseEntity<String> json(ObjectNode doc) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(doc.toString());
    }
}
