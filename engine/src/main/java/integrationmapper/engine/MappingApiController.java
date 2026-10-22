package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console API for the mapping board (step 4): everything the board shows, link autosave (D-1), and test runs. */
@RestController
@RequestMapping("/admin/api/integrations/{slug}")
public class MappingApiController {

    private final ConfigStore store;
    private final PayloadLibrary library;
    private final TestRunner tests;
    private final IntegrationPublisher publisher;

    public MappingApiController(ConfigStore store, PayloadLibrary library, TestRunner tests, IntegrationPublisher publisher) {
        this.store = store;
        this.library = library;
        this.tests = tests;
        this.publisher = publisher;
    }

    /** The draft, both payload definitions and the chosen operation, in one call. */
    @GetMapping("/board")
    public ResponseEntity<String> board(@PathVariable String slug) throws IOException {
        ObjectNode d = draft(slug);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draft", d);
        out.put("input", d.hasNonNull("input") ? library.get(d.get("input").asText()) : null);
        out.put("output", d.hasNonNull("output") ? library.get(d.get("output").asText()) : null);
        out.put("operation", operation(d));
        return json(out);
    }

    record Links(List<ServiceDefinition.Link> request, List<ServiceDefinition.Link> response) {
    }

    /**
     * Saves the links as they are, including red or broken ones (they stay visible with their warning, D-6, D-7);
     * publishing refuses them (R8).
     */
    @PutMapping("/links")
    public ResponseEntity<String> saveLinks(@PathVariable String slug, @RequestBody String body) throws IOException {
        Links links = read(body, Links.class);
        ObjectNode d = draft(slug);
        d.putObject("request").set("links", Json.MAPPER.valueToTree(links.request() == null ? List.of() : links.request()));
        d.putObject("response").set("links", Json.MAPPER.valueToTree(links.response() == null ? List.of() : links.response()));
        store.writeJson(store.service(slug), d);
        return json(d);
    }

    record TestRequest(JsonNode input, boolean real) {
    }

    @PostMapping("/test")
    public ResponseEntity<String> test(@PathVariable String slug, @RequestBody String body) throws IOException {
        TestRequest req = read(body, TestRequest.class);
        draft(slug); // 400 for an unknown integration
        return json(tests.run(slug, req.input() == null ? Json.MAPPER.createObjectNode() : req.input(), req.real()));
    }

    /** Deletes the integration: its live endpoint, draft, versions and secret (IntegrationPublisher.delete). */
    @DeleteMapping
    public ResponseEntity<String> delete(@PathVariable String slug) throws Exception {
        draft(slug);
        publisher.delete(slug);
        return ResponseEntity.noContent().build();
    }

    /** Step 5: publishes the draft as the next version (R8: the engine refuses broken links, with the list). */
    @PostMapping("/publish")
    public ResponseEntity<String> publish(@PathVariable String slug) throws Exception {
        draft(slug);
        publisher.publishDraft(slug);
        return versions(slug);
    }

    /** Step 5 rollback: makes an earlier version live again, without writing a new version file. */
    @PostMapping("/versions/{version}/activate")
    public ResponseEntity<String> activate(@PathVariable String slug, @PathVariable String version) throws Exception {
        draft(slug);
        publisher.activateVersion(slug, version);
        return versions(slug);
    }

    record VersionInfo(String version, String publishedAt) {
    }

    /** Published versions, newest first, and which one is live. */
    @GetMapping("/versions")
    public ResponseEntity<String> versions(@PathVariable String slug) throws IOException {
        ObjectNode d = draft(slug);
        List<VersionInfo> list = new java.util.ArrayList<>();
        for (String v : store.versions(slug).reversed()) {
            JsonNode snapshot = Json.MAPPER.readTree(store.version(slug, v).toFile());
            list.add(new VersionInfo(v, snapshot.path("publishedAt").asText(null)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeVersion", d.path("activeVersion").asText(null));
        out.put("versions", list);
        return json(out);
    }

    // ---- helpers

    private Contract.Operation operation(JsonNode d) throws IOException {
        if (!d.hasNonNull("contract") || !d.hasNonNull("operationId")) {
            return null;
        }
        Path p = store.root().resolve("contracts").resolve(d.get("contract").asText() + ".json");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        String opId = d.get("operationId").asText();
        return Json.MAPPER.readValue(p.toFile(), Contract.class).operations().stream()
                .filter(o -> o.id().equals(opId)).findFirst().orElse(null);
    }

    private ObjectNode draft(String slug) throws IOException {
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*") || !Files.isRegularFile(store.service(slug))) {
            throw new IllegalArgumentException("No integration " + slug);
        }
        return (ObjectNode) Json.MAPPER.readTree(store.service(slug).toFile());
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad-request");
        body.put("detail", e.getMessage());
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Json.MAPPER.writeValueAsString(body));
    }
}
