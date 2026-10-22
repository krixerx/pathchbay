package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

/**
 * Console API for Swagger configurations and their connections (D-26), choosing the operation, and the integration's
 * Settings page (D-3): auth and secret. Same body handling as {@link ConsoleApiController} (engine's Jackson 2).
 */
@RestController
@RequestMapping("/admin/api")
public class ContractApiController {

    private final ConfigStore store;
    private final ContractImporter importer;
    private final SecretResolver secrets;
    private final PayloadLibrary library;
    private final Connections connections;
    private final IntegrationPublisher publisher;
    private final IntegrationRegistry registry;

    public ContractApiController(ConfigStore store, ContractImporter importer, SecretResolver secrets, PayloadLibrary library,
            Connections connections, IntegrationPublisher publisher, IntegrationRegistry registry) {
        this.store = store;
        this.importer = importer;
        this.secrets = secrets;
        this.library = library;
        this.connections = connections;
        this.publisher = publisher;
        this.registry = registry;
    }

    // ---- contracts (step 2)

    /**
     * {@code usedBy}: integrations whose draft calls an operation of this contract. {@code baseUrl}: its connection's
     * address in this environment, null when not set; {@code baseUrlInEnv} when it comes from the environment.
     */
    public record ContractSummary(String id, String name, int operationCount, List<String> servers, int warningCount,
            String importedAt, List<String> usedBy, String baseUrl, boolean baseUrlInEnv) {
    }

    @GetMapping("/contracts")
    public ResponseEntity<String> contracts() throws IOException {
        List<ContractSummary> out = new ArrayList<>();
        for (Contract c : allContracts()) {
            out.add(new ContractSummary(c.id(), c.name(), c.operations().size(), c.servers(),
                    c.warnings() == null ? 0 : c.warnings().size(), c.importedAt(), usedBy(c.id()), connections.baseUrl(c.id()),
                    connections.fromEnv(c.id()) != null));
        }
        return json(out);
    }

    @GetMapping("/contracts/{id}")
    public ResponseEntity<String> contract(@PathVariable String id) throws IOException {
        return json(readContract(id));
    }

    /** Deletes an imported contract and its original file; refused while an integration still uses it. */
    @DeleteMapping("/contracts/{id}")
    public ResponseEntity<String> deleteContract(@PathVariable String id) throws IOException {
        readContract(id);
        List<String> users = usedBy(id);
        if (!users.isEmpty()) {
            throw new IllegalArgumentException("Contract " + id + " is used by " + String.join(", ", users)
                    + ". Pick another operation there or delete those integrations first.");
        }
        Path dir = store.root().resolve("contracts");
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith(id + ".")).toList()) {
                String rest = p.getFileName().toString().substring(id.length());
                if (rest.equals(".json") || rest.startsWith(".openapi.")) {
                    Files.delete(p);
                }
            }
        }
        return ResponseEntity.noContent().build();
    }

    private List<String> usedBy(String contractId) throws IOException {
        Path dir = store.root().resolve("services");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                JsonNode d = Json.MAPPER.readTree(p.toFile());
                if (contractId.equals(d.path("contract").asText(null))) {
                    out.add(d.path("id").asText());
                }
            }
        }
        return out;
    }

    record ImportRequest(String name, String url, String content) {
    }

    /** Import from pasted/uploaded text, or from a URL fetched by the engine. Same name = re-import (replaces). */
    @PostMapping("/contracts")
    public ResponseEntity<String> importContract(@RequestBody String body) throws IOException {
        ImportRequest req = read(body, ImportRequest.class);
        String id = ConsoleApiController.slugify(req.name());
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Give the contract a name, for example the name of the external system");
        }
        String text = req.content() != null && !req.content().isBlank() ? req.content()
                : req.url() != null && !req.url().isBlank() ? importer.fetch(req.url().strip())
                : null;
        if (text == null) {
            throw new IllegalArgumentException("Upload a file or give a URL");
        }
        return json(importer.importContract(id, req.name().strip(), text));
    }

    /**
     * Edits a contract in place: a new name, and optionally a new file (upload or URL) read again. The id stays, so
     * integrations keep pointing at it; live versions are not touched. The response lists, as transient
     * {@code notes}, integrations whose operation is not in the new file.
     */
    @PutMapping("/contracts/{id}")
    public ResponseEntity<String> updateContract(@PathVariable String id, @RequestBody String body) throws IOException {
        Contract old = readContract(id);
        ImportRequest req = read(body, ImportRequest.class);
        String name = req.name() == null || req.name().isBlank() ? old.name() : req.name().strip();
        String text = req.content() != null && !req.content().isBlank() ? req.content()
                : req.url() != null && !req.url().isBlank() ? importer.fetch(req.url().strip())
                : null;
        Contract updated;
        if (text == null) {
            updated = new Contract(id, name, old.sourceFile(), old.importedAt(), old.servers(), old.operations(), old.warnings());
            store.writeJson(store.root().resolve("contracts").resolve(id + ".json"), updated);
        } else {
            updated = importer.importContract(id, name, text);
            if (old.sourceFile() != null && !old.sourceFile().equals(updated.sourceFile())) {
                Files.deleteIfExists(store.root().resolve("contracts").resolve(old.sourceFile()));
            }
        }
        ObjectNode out = Json.MAPPER.valueToTree(updated);
        ArrayNode notes = out.putArray("notes");
        Set<String> ops = updated.operations().stream().map(Contract.Operation::id).collect(Collectors.toSet());
        for (String slug : usedBy(id)) {
            String opId = draft(slug).path("operationId").asText(null);
            if (opId != null && !ops.contains(opId)) {
                notes.add("Integration " + slug + " calls " + opId + ", which is not in the new file. Pick another operation on its board;"
                        + " its live version keeps working until you publish again.");
            }
        }
        return json(out);
    }

    // ---- connections (D-26)

    /**
     * A connection: its base URL and headers (D-27) in this environment and where they come from. {@code usedBy}:
     * drafts and live versions calling through it; a change reaches the live ones at once.
     */
    @GetMapping("/connections/{id}")
    public ResponseEntity<String> connection(@PathVariable String id) throws IOException {
        checkConnectionId(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("baseUrl", connections.baseUrl(id));
        out.put("savedUrl", connections.fromFile(id));
        out.put("envName", Connections.envName(id));
        out.put("inEnv", connections.fromEnv(id) != null);
        out.put("headers", connections.headerSettings(id));
        Path contract = store.root().resolve("contracts").resolve(id + ".json");
        out.put("servers", Files.isRegularFile(contract) ? readContract(id).servers() : List.of());
        out.put("usedBy", connectionUsers(id));
        return json(out);
    }

    /** {@code headers} null leaves the headers as they are. */
    record ConnectionUpdate(String baseUrl, List<Connections.HeaderUpdate> headers) {
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<String> saveConnection(@PathVariable String id, @RequestBody String body) throws Exception {
        checkConnectionId(id);
        ConnectionUpdate req = read(body, ConnectionUpdate.class);
        boolean clearing = req.baseUrl() == null || req.baseUrl().isBlank();
        if (clearing && connections.fromEnv(id) == null) {
            List<String> live = liveUsers(id);
            if (!live.isEmpty()) {
                throw new IllegalArgumentException("Live integrations call through this connection (" + String.join(", ", live)
                        + "), so it needs a base URL");
            }
        }
        connections.save(id, req.baseUrl(), req.headers());
        if (connections.baseUrl(id) != null) {
            publisher.reconnect(id);
        }
        return connection(id);
    }

    private void checkConnectionId(String id) {
        if (id == null || !id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("No connection " + id);
        }
    }

    private List<String> liveUsers(String id) {
        return registry.active().values().stream().map(CompiledIntegration::definition)
                .filter(d -> id.equals(d.target().connection())).map(ServiceDefinition::id).toList();
    }

    private List<String> connectionUsers(String id) throws IOException {
        java.util.TreeSet<String> out = new java.util.TreeSet<>(liveUsers(id));
        Path dir = store.root().resolve("services");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    JsonNode d = Json.MAPPER.readTree(p.toFile());
                    if (id.equals(d.path("target").path("connection").asText(null))) {
                        out.add(d.path("id").asText());
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    // ---- operation (step 3)

    record OperationChoice(String contract, String operationId) {
    }

    /**
     * Sets the contract and operation of an integration: method and path come from the operation, the base URL from
     * the contract's connection (D-26). A connection without a URL yet gets the contract's first absolute server
     * address. An Input or Output left
     * empty in step 1 ("fill from the API later") is created from the operation, already linked (see {@link Prefill}).
     * The response is the draft plus {@code notes} about the prefill; the notes are not stored.
     */
    @PutMapping("/integrations/{slug}/operation")
    public ResponseEntity<String> chooseOperation(@PathVariable String slug, @RequestBody String body) throws IOException {
        OperationChoice req = read(body, OperationChoice.class);
        Contract c = readContract(req.contract());
        Contract.Operation op = c.operations().stream().filter(o -> o.id().equals(req.operationId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Contract " + c.id() + " has no operation " + req.operationId()));
        ObjectNode d = draft(slug);
        d.put("contract", c.id());
        d.put("operationId", op.id());
        ObjectNode target = d.has("target") && d.get("target").isObject() ? (ObjectNode) d.get("target") : d.putObject("target");
        target.put("method", op.method());
        target.put("path", op.path());
        target.put("connection", c.id());
        target.remove("baseUrl");
        if (connections.baseUrl(c.id()) == null) {
            String first = c.servers().stream().filter(s -> s.startsWith("http://") || s.startsWith("https://")).findFirst().orElse(null);
            if (first != null) {
                connections.save(c.id(), first);
            }
        }
        List<String> notes = new ArrayList<>();
        String name = d.path("name").asText(slug);
        if (!d.hasNonNull("input")) {
            Prefill.Result r = Prefill.input(freeDefinitionId(slug + "-input"), name + " input", op);
            saveDefinition(r.definition());
            d.put("input", r.definition().id());
            d.putObject("request").set("links", Json.MAPPER.valueToTree(r.links()));
            notes.add("Input " + r.definition().id() + (r.links().isEmpty() ? " was created empty"
                    : " was created from the operation with " + r.links().size() + " links"));
            notes.addAll(r.notes());
        }
        if (!d.hasNonNull("output")) {
            Prefill.Result r = Prefill.output(freeDefinitionId(slug + "-output"), name + " output", op);
            saveDefinition(r.definition());
            d.put("output", r.definition().id());
            d.putObject("response").set("links", Json.MAPPER.valueToTree(r.links()));
            notes.add("Output " + r.definition().id() + (r.links().isEmpty() ? " was created empty"
                    : " was created from the operation with " + r.links().size() + " links"));
            notes.addAll(r.notes());
        }
        store.writeJson(store.service(slug), d);
        ObjectNode out = d.deepCopy();
        notes.forEach(out.putArray("notes")::add);
        return json(out);
    }

    private Path definitionFile(String id) {
        return store.root().resolve("entities").resolve(id + ".json");
    }

    /** {@code base}, or {@code base-2}, {@code base-3}, ... so an existing definition is never overwritten. */
    private String freeDefinitionId(String base) {
        String id = base;
        for (int n = 2; Files.exists(definitionFile(id)); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private void saveDefinition(PayloadDefinition def) throws IOException {
        def.check();
        store.writeJson(definitionFile(def.id()), def);
        library.put(def);
    }

    // ---- settings page (D-3)

    /**
     * {@code connection}: the connection the integration calls through (its Swagger configuration), whose base URL is
     * set under Configuration; null for files from before connections, which still carry {@code baseUrl} themselves.
     */
    @GetMapping("/integrations/{slug}/settings")
    public ResponseEntity<String> settings(@PathVariable String slug) throws IOException {
        ObjectNode d = draft(slug);
        String connection = d.path("target").path("connection").asText(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection", connection);
        out.put("baseUrl", connection != null ? connections.baseUrl(connection) : d.path("target").path("baseUrl").asText(null));
        out.put("auth", d.has("auth") ? d.get("auth") : null);
        out.put("secretEnvName", SecretResolver.envName(slug));
        out.put("secretInEnv", secrets.inEnv(slug));
        out.put("secretInFile", secrets.inFile(slug));
        return json(out);
    }

    record SettingsUpdate(String baseUrl, JsonNode auth, String secret, Boolean removeSecret) {
    }

    @PutMapping("/integrations/{slug}/settings")
    public ResponseEntity<String> saveSettings(@PathVariable String slug, @RequestBody String body) throws IOException {
        SettingsUpdate req = read(body, SettingsUpdate.class);
        ObjectNode d = draft(slug);
        if (req.baseUrl() != null) {
            if (d.path("target").hasNonNull("connection")) {
                throw new IllegalArgumentException("This integration's base URL comes from its connection; set it under Configuration");
            }
            String url = req.baseUrl().strip();
            if (!url.isEmpty()) {
                Connections.check(url);
            }
            ObjectNode target = d.has("target") && d.get("target").isObject() ? (ObjectNode) d.get("target") : d.putObject("target");
            target.put("baseUrl", url);
        }
        if (req.auth() == null || req.auth().isNull()) {
            d.remove("auth");
        } else {
            ServiceDefinition.Auth auth = Json.MAPPER.treeToValue(req.auth(), ServiceDefinition.Auth.class);
            auth.check(slug);
            d.set("auth", Json.MAPPER.valueToTree(auth));
        }
        store.writeJson(store.service(slug), d);
        if (Boolean.TRUE.equals(req.removeSecret())) {
            secrets.save(slug, null);
        } else if (req.secret() != null && !req.secret().isBlank()) {
            secrets.save(slug, req.secret());
        }
        return settings(slug);
    }

    // ---- helpers

    private List<Contract> allContracts() throws IOException {
        Path dir = store.root().resolve("contracts");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Contract> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".json") && !p.getFileName().toString().contains(".openapi.")).sorted().toList()) {
                out.add(Json.MAPPER.readValue(p.toFile(), Contract.class));
            }
        }
        return out;
    }

    private Contract readContract(String id) throws IOException {
        Path p = store.root().resolve("contracts").resolve(id + ".json");
        if (id == null || !id.matches("[a-z0-9]+(-[a-z0-9]+)*") || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("No contract " + id);
        }
        return Json.MAPPER.readValue(p.toFile(), Contract.class);
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
