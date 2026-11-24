package integrationmapper.engine.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


import org.apache.camel.CamelContext;
import org.springframework.stereotype.Component;

import integrationmapper.engine.config.ConfigStore;
import integrationmapper.engine.config.Connections;
import integrationmapper.engine.config.Drafts;
import integrationmapper.engine.config.PayloadLibrary;
import integrationmapper.engine.config.SecretResolver;
import integrationmapper.engine.format.Json;
import integrationmapper.engine.mapping.ContractShapes;
import integrationmapper.engine.model.Contract;
import integrationmapper.engine.model.Operation;
import integrationmapper.engine.model.ServiceDefinition;
import integrationmapper.engine.model.Version;
import integrationmapper.engine.model.VersionSnapshot;
import integrationmapper.engine.web.IntegrationGate;

/**
 * Publishing and activating versions. Order (R9): build and compile first; only when that works write the version
 * file, then {@code activeVersion} in the service file, then swap the route behind the gate (R7). A failure before
 * the swap leaves files and the live version unchanged. A failed swap puts the previous route back and undoes both
 * writes, so on any failure nothing on disk changes and the old version keeps serving (R9). One editor at a time
 * (R3): calls are serialized here; the service file itself is written only through {@link Drafts}.
 */
@Component
public class IntegrationPublisher {

    private static final Set<String> QUERY_METHODS = Set.of("GET", "DELETE");

    private final CamelContext camel;
    private final IntegrationGate gate;
    private final IntegrationRegistry registry;
    private final PayloadLibrary library;
    private final ConfigStore store;
    private final SecretResolver secrets;
    private final Connections connections;
    private final Drafts drafts;

    public IntegrationPublisher(CamelContext camel, IntegrationGate gate, IntegrationRegistry registry,
            PayloadLibrary library, ConfigStore store, SecretResolver secrets, Connections connections, Drafts drafts) {
        this.camel = camel;
        this.gate = gate;
        this.registry = registry;
        this.library = library;
        this.store = store;
        this.secrets = secrets;
        this.connections = connections;
        this.drafts = drafts;
    }

    /** Publishes the draft {@code services/<slug>.json} as the next version and makes it live. Returns the version. */
    public synchronized String publishDraft(String slug) throws Exception {
        ServiceDefinition draft = readDraft(slug);
        List<String> existing = store.versions(slug);
        String version = existing.isEmpty() ? "1.0.0"
                : Version.parse(existing.get(existing.size() - 1)).nextMinor().toString();
        VersionSnapshot snapshot = new VersionSnapshot(version, OffsetDateTime.now(ZoneOffset.UTC).toString(),
                draft.withVersion(version), library.get(draft.input()), library.get(draft.output()));

        // The shape rule needs the contract's types, which only the draft knows; its problems come with the compile
        // problems, so every problem is listed at once (R8).
        List<String> shapes = ContractShapes.problems(snapshot.service(), snapshot.input(), snapshot.output(), draftOperation(slug));
        CompiledIntegration compiled;
        try {
            compiled = compile(snapshot);
        } catch (IllegalArgumentException e) {
            if (shapes.isEmpty()) {
                throw e;
            }
            throw new IllegalArgumentException(e.getMessage() + "; " + String.join("; ", shapes), e);
        }
        if (!shapes.isEmpty()) {
            throw new IllegalArgumentException("Integration " + slug + " " + version + " does not compile: " + String.join("; ", shapes));
        }
        store.writeJson(store.version(slug, version), snapshot);
        String previous = setActiveVersion(slug, version);
        try {
            goLive(compiled);
        } catch (Exception e) {
            setActiveVersion(slug, previous);
            Files.deleteIfExists(store.version(slug, version));
            throw e;
        }
        return version;
    }

    /**
     * The contract operation a draft was built from, or null: the draft names it (contract, operationId), the
     * published version doesn't. Null too when the contract is gone, so publishing doesn't depend on it.
     */
    private Operation draftOperation(String slug) throws IOException {
        var draft = Json.MAPPER.readTree(store.service(slug).toFile());
        if (!draft.hasNonNull("contract") || !draft.hasNonNull("operationId")) {
            return null;
        }
        var file = store.root().resolve("contracts").resolve(draft.get("contract").asText() + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String opId = draft.get("operationId").asText();
        return Json.MAPPER.readValue(file.toFile(), Contract.class).operations().stream()
                .filter(o -> o.id().equals(opId)).findFirst().orElse(null);
    }

    /** Makes a stored version live again ("Make active", rollback). No new version file is written. */
    public synchronized void activateVersion(String slug, String version) throws Exception {
        CompiledIntegration compiled = compile(readSnapshot(slug, version));
        String previous = setActiveVersion(slug, version);
        try {
            goLive(compiled);
        } catch (Exception e) {
            setActiveVersion(slug, previous);
            throw e;
        }
    }

    /** At startup: serves the active version of one integration. Returns false for a draft that was never published. */
    public synchronized boolean loadActive(String slug) throws Exception {
        String active = readDraft(slug).activeVersion();
        if (active == null) {
            return false;
        }
        goLive(compile(readSnapshot(slug, active)));
        return true;
    }

    /**
     * Makes a definition live from the current library without writing any files. Used by tests and for checking a
     * draft; publishing goes through {@link #publishDraft}.
     */
    public synchronized void publish(ServiceDefinition def) throws Exception {
        goLive(compile(new VersionSnapshot(def.version(), null, def, library.get(def.input()), library.get(def.output()))));
    }

    private CompiledIntegration compile(VersionSnapshot s) {
        ServiceDefinition def = s.service();
        String method = def.target().method();
        if (!IntegrationRoutes.METHODS.contains(method)) {
            throw new IllegalArgumentException("Integration " + def.id() + ": unsupported method " + method);
        }
        if (QUERY_METHODS.contains(method) && !s.input().isFlat()) {
            throw new IllegalArgumentException("Integration " + def.id() + ": callers of a " + method
                    + " integration send the Input as query parameters, so Input " + s.input().id()
                    + " can only have simple fields.");
        }
        String secret = null;
        if (def.auth() != null) {
            def.auth().check(def.id());
            secret = secrets.resolve(def.id());
            def.auth().checkSecret(def.id(), secret);
        }
        return CompiledIntegration.compile(def, s.input(), s.output(), secret, connections.resolve(def), connections.headers(def));
    }

    /**
     * After a connection's base URL or headers changed: every live integration using it goes live again with the new
     * values, same version, one at a time behind its gate. Returns the slugs that were switched.
     */
    public synchronized List<String> reconnect(String connection) throws Exception {
        List<String> switched = new ArrayList<>();
        for (CompiledIntegration ci : registry.active().values()) {
            ServiceDefinition def = ci.definition();
            if (connection.equals(def.target().connection())) {
                goLive(CompiledIntegration.compile(def, ci.input(), ci.output(), ci.secret(), connections.resolve(def),
                        connections.headers(def)));
                switched.add(def.id());
            }
        }
        return switched;
    }

    /**
     * Deletes an integration: takes its route down (callers then get 404) and removes its draft, every version file
     * and its stored secret. Payload definitions and contracts stay; they belong to the library.
     */
    public synchronized void delete(String slug) throws Exception {
        if (!Files.isRegularFile(store.service(slug))) {
            throw new IllegalArgumentException("No integration " + slug);
        }
        String routeId = "int-" + slug;
        gate.swap(slug, () -> {
            if (camel.getRoute(routeId) != null) {
                camel.getRouteController().stopRoute(routeId);
                camel.removeRoute(routeId);
            }
            registry.remove(slug);
        });
        for (String v : store.versions(slug)) {
            Files.deleteIfExists(store.version(slug, v));
        }
        Files.deleteIfExists(store.versionsDir(slug));
        Files.deleteIfExists(store.secret(slug));
        try (Drafts.Lock locked = drafts.lock(slug)) {
            Files.delete(store.service(slug));
        }
    }

    /**
     * Swaps the route behind the gate. When the new route can't be added, the previous version's route is added back
     * and the exception is rethrown; if even that fails, the integration is marked broken (R10).
     */
    private void goLive(CompiledIntegration compiled) throws Exception {
        String slug = compiled.definition().id();
        String routeId = "int-" + slug;
        gate.swap(slug, () -> {
            CompiledIntegration previous = registry.find(slug);
            removeRoute(routeId);
            try {
                addRoute(routeId, compiled);
            } catch (Exception e) {
                removeRoute(routeId);
                if (previous != null) {
                    try {
                        addRoute(routeId, previous);
                    } catch (Exception restore) {
                        removeRoute(routeId);
                        registry.remove(slug);
                        registry.markBroken(slug, "the previous version could not be restored after a failed publish: "
                                + restore.getMessage());
                        e.addSuppressed(restore);
                    }
                }
                throw e;
            }
            registry.activate(compiled);
        });
    }

    private void addRoute(String routeId, CompiledIntegration ci) throws Exception {
        camel.addRouteFromTemplate(routeId, IntegrationRoutes.templateId(ci.definition().target().method()),
                Map.of("slug", ci.definition().id(), "baseUrl", ci.baseUrl()));
    }

    private void removeRoute(String routeId) throws Exception {
        if (camel.getRoute(routeId) != null) {
            camel.getRouteController().stopRoute(routeId);
            camel.removeRoute(routeId);
        }
    }

    private ServiceDefinition readDraft(String slug) throws IOException {
        if (!Files.isRegularFile(store.service(slug))) {
            throw new IllegalArgumentException("No service file " + store.service(slug));
        }
        ServiceDefinition def = Json.MAPPER.readValue(store.service(slug).toFile(), ServiceDefinition.class);
        if (!slug.equals(def.id())) {
            throw new IllegalArgumentException("Service file " + store.service(slug) + " has id " + def.id() + ", expected " + slug);
        }
        return def;
    }

    private VersionSnapshot readSnapshot(String slug, String version) throws IOException {
        if (!Files.isRegularFile(store.version(slug, version))) {
            throw new IllegalArgumentException("Integration " + slug + " has no version " + version);
        }
        VersionSnapshot s = Json.MAPPER.readValue(store.version(slug, version).toFile(), VersionSnapshot.class);
        s.input().check();
        s.output().check();
        return s;
    }

    /**
     * Rewrites only {@code activeVersion} in the service file (null removes it), keeping everything else as it is.
     * Returns the value it replaced.
     */
    private String setActiveVersion(String slug, String version) throws IOException {
        String[] previous = new String[1];
        drafts.edit(slug, d -> {
            previous[0] = d.path("activeVersion").asText(null);
            if (version == null) {
                d.remove("activeVersion");
            } else {
                d.put("activeVersion", version);
            }
        });
        return previous[0];
    }
}
