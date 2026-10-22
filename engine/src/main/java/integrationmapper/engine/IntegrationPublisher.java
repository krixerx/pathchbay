package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.camel.CamelContext;
import org.springframework.stereotype.Component;

/**
 * Publishing and activating versions. Order (R9): build and compile first; only when that works write the version
 * file, then {@code activeVersion} in the service file, then swap the route behind the gate (R7). A failure before
 * the swap leaves files and the live version unchanged. One editor at a time (R3): calls are serialized here.
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

    public IntegrationPublisher(CamelContext camel, IntegrationGate gate, IntegrationRegistry registry,
            PayloadLibrary library, ConfigStore store, SecretResolver secrets) {
        this.camel = camel;
        this.gate = gate;
        this.registry = registry;
        this.library = library;
        this.store = store;
        this.secrets = secrets;
    }

    /** Publishes the draft {@code services/<slug>.json} as the next version and makes it live. Returns the version. */
    public synchronized String publishDraft(String slug) throws Exception {
        ServiceDefinition draft = readDraft(slug);
        List<String> existing = store.versions(slug);
        String version = existing.isEmpty() ? "1.0.0"
                : Version.parse(existing.get(existing.size() - 1)).nextMinor().toString();
        VersionSnapshot snapshot = new VersionSnapshot(version, OffsetDateTime.now(ZoneOffset.UTC).toString(),
                draft.withVersion(version), library.get(draft.input()), library.get(draft.output()));

        CompiledIntegration compiled = compile(snapshot);
        store.writeJson(store.version(slug, version), snapshot);
        setActiveVersion(slug, version);
        goLive(compiled);
        return version;
    }

    /** Makes a stored version live again ("Make active", rollback). No new version file is written. */
    public synchronized void activateVersion(String slug, String version) throws Exception {
        CompiledIntegration compiled = compile(readSnapshot(slug, version));
        setActiveVersion(slug, version);
        goLive(compiled);
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
        }
        return CompiledIntegration.compile(def, s.input(), s.output(), secret);
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
        Files.delete(store.service(slug));
    }

    private void goLive(CompiledIntegration compiled) throws Exception {
        ServiceDefinition def = compiled.definition();
        String routeId = "int-" + def.id();
        gate.swap(def.id(), () -> {
            if (camel.getRoute(routeId) != null) {
                camel.getRouteController().stopRoute(routeId);
                camel.removeRoute(routeId);
            }
            registry.activate(compiled);
            camel.addRouteFromTemplate(routeId, IntegrationRoutes.templateId(def.target().method()),
                    Map.of("slug", def.id(), "baseUrl", def.target().baseUrl()));
        });
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

    /** Rewrites only {@code activeVersion} in the service file, keeping everything else as it is. */
    private void setActiveVersion(String slug, String version) throws IOException {
        ObjectNode file = (ObjectNode) Json.MAPPER.readTree(store.service(slug).toFile());
        file.put("activeVersion", version);
        store.writeJson(store.service(slug), file);
    }
}
