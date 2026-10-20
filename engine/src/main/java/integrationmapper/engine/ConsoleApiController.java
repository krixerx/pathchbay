package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the console reads and writes. Everything is the same JSON files the engine runs from (ADR-004); this API only
 * lists, validates and saves them. One editor at a time (R3): the last save wins.
 *
 * <p>Bodies are read and written with the engine's own Jackson 2 mapper ({@link Json#MAPPER}), the one the config
 * files use, rather than Spring Boot 4's Jackson 3, so the console sees exactly what is stored.
 */
@RestController
@RequestMapping("/admin/api")
public class ConsoleApiController {

    private static final String SLUG = "[a-z0-9]+(-[a-z0-9]+)*";

    private final ConfigStore store;
    private final PayloadLibrary library;
    private final IntegrationRegistry registry;

    public ConsoleApiController(ConfigStore store, PayloadLibrary library, IntegrationRegistry registry) {
        this.store = store;
        this.library = library;
        this.registry = registry;
    }

    // ---- payload definitions (D-8, D-9, D-13)

    public record DefinitionSummary(String id, String name, int fieldCount, List<String> usedBy) {
    }

    @GetMapping("/definitions")
    public ResponseEntity<String> definitions() throws IOException {
        List<DefinitionSummary> out = new ArrayList<>();
        for (PayloadDefinition d : library.all()) {
            out.add(new DefinitionSummary(d.id(), d.name(), d.fields() == null ? 0 : d.fields().size(), usedBy(d.id())));
        }
        return json(out);
    }

    @GetMapping("/definitions/{id}")
    public ResponseEntity<String> definition(@PathVariable String id) throws IOException {
        return json(Map.of("definition", library.get(id), "usedBy", usedBy(id)));
    }

    /** Creates or replaces a definition. Live versions keep their own copy (D-9); drafts see the change. */
    @PutMapping("/definitions/{id}")
    public ResponseEntity<String> saveDefinition(@PathVariable String id, @RequestBody String body) throws IOException {
        if (!id.matches(SLUG)) {
            throw new IllegalArgumentException("Definition id must be lower-case letters, digits and dashes: " + id);
        }
        PayloadDefinition def = read(body, PayloadDefinition.class);
        if (!id.equals(def.id())) {
            throw new IllegalArgumentException("Definition id " + def.id() + " does not match " + id);
        }
        def.check();
        store.writeJson(store.root().resolve("entities").resolve(id + ".json"), def);
        library.put(def);
        return json(Map.of("definition", def, "usedBy", usedBy(id)));
    }

    record InferRequest(String id, String name, JsonNode sample) {
    }

    @PostMapping("/definitions/infer")
    public ResponseEntity<String> infer(@RequestBody String body) throws IOException {
        InferRequest req = read(body, InferRequest.class);
        SampleInference.Result r = SampleInference.infer(req.id(), req.name(), req.sample());
        return json(Map.of("definition", r.definition(), "guesses", r.guesses()));
    }

    // ---- integrations (D-1)

    public record IntegrationSummary(String id, String name, String input, String output, String method,
            String activeVersion, String status, String problem) {
    }

    @GetMapping("/integrations")
    public ResponseEntity<String> integrations() throws IOException {
        List<IntegrationSummary> out = new ArrayList<>();
        Map<String, String> live = registry.activeVersions();
        Map<String, String> broken = registry.brokenReasons();
        for (String slug : slugs()) {
            JsonNode d = draft(slug);
            String status = broken.containsKey(slug) ? "broken" : live.containsKey(slug) ? "live" : "draft";
            out.add(new IntegrationSummary(slug, text(d, "name"), text(d, "input"), text(d, "output"),
                    d.path("target").path("method").asText(null), live.get(slug), status, broken.get(slug)));
        }
        return json(out);
    }

    @GetMapping("/integrations/{slug}")
    public ResponseEntity<String> integration(@PathVariable String slug) throws IOException {
        return json(draft(slug));
    }

    record NewIntegration(String name) {
    }

    /** "New integration": the slug comes from the name the user types (D-1). */
    @PostMapping("/integrations")
    public ResponseEntity<String> create(@RequestBody String body) throws IOException {
        NewIntegration req = read(body, NewIntegration.class);
        String slug = slugify(req.name());
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Give the integration a name with at least one letter or digit");
        }
        if (Files.exists(store.service(slug))) {
            throw new IllegalArgumentException("An integration called " + slug + " already exists");
        }
        ObjectNode draft = Json.MAPPER.createObjectNode().put("id", slug).put("name", req.name().strip());
        draft.putObject("request").putArray("links");
        draft.putObject("response").putArray("links");
        store.writeJson(store.service(slug), draft);
        return json(draft);
    }

    record Payloads(String input, String output) {
    }

    /** Step 1: which Input and Output definitions the integration uses (D-9). Autosaved by the console (D-1). */
    @PutMapping("/integrations/{slug}/payloads")
    public ResponseEntity<String> setPayloads(@PathVariable String slug, @RequestBody String body) throws IOException {
        Payloads req = read(body, Payloads.class);
        ObjectNode d = draft(slug);
        for (String id : new String[] {req.input(), req.output()}) {
            if (id != null && !id.isBlank()) {
                library.get(id); // fails with a clear message if unknown
            }
        }
        putOrRemove(d, "input", req.input());
        putOrRemove(d, "output", req.output());
        store.writeJson(store.service(slug), d);
        return json(d);
    }

    // ---- helpers

    static String slugify(String name) {
        return name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    private static ResponseEntity<String> json(Object body) throws JsonProcessingException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Json.MAPPER.writeValueAsString(body));
    }

    private static <T> T read(String body, Class<T> type) {
        try {
            return Json.MAPPER.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body is not valid: " + e.getOriginalMessage());
        }
    }

    private List<String> slugs() throws IOException {
        Path dir = store.root().resolve("services");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted().toList();
        }
    }

    private ObjectNode draft(String slug) throws IOException {
        if (!slug.matches(SLUG) || !Files.isRegularFile(store.service(slug))) {
            throw new IllegalArgumentException("No integration " + slug);
        }
        return (ObjectNode) Json.MAPPER.readTree(store.service(slug).toFile());
    }

    /** Integrations whose draft uses the definition as Input or Output. */
    private List<String> usedBy(String definitionId) throws IOException {
        List<String> out = new ArrayList<>();
        for (String slug : slugs()) {
            JsonNode d = draft(slug);
            if (definitionId.equals(text(d, "input")) || definitionId.equals(text(d, "output"))) {
                out.add(slug);
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static void putOrRemove(ObjectNode n, String field, String value) {
        if (value == null || value.isBlank()) {
            n.remove(field);
        } else {
            n.put(field, value);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad-request");
        body.put("detail", e.getMessage());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Json.MAPPER.writeValueAsString(body));
    }
}
