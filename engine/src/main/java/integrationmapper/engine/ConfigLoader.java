package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup loads the payload library ({@code <config-dir>/entities/*.json}), then serves the active version of
 * every {@code <config-dir>/services/*.json} from its version file. A broken file is skipped and reported, the
 * others still start (R10).
 * <p>
 * With {@code mapper.import-dir} ({@code MAPPER_IMPORT_DIR}) set, every {@code *.zip} package in that folder is
 * imported first, in name order, like {@code POST /admin/api/import} (D-31). An integration that has no live version
 * here then gets the version that was live where the package came from. A package that can't be imported, or a
 * version that can't go live (a missing secret or base URL), is logged and skipped; the others still start.
 */
@Component
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private final IntegrationPublisher publisher;
    private final IntegrationRegistry registry;
    private final PayloadLibrary library;
    private final Connections connections;
    private final ConfigStore store;
    private final Packages packages;
    private final Path configDir;
    private final String importDir;

    public ConfigLoader(IntegrationPublisher publisher, IntegrationRegistry registry, PayloadLibrary library,
            Connections connections, ConfigStore store, Packages packages,
            @Value("${mapper.config-dir:config}") String configDir, @Value("${mapper.import-dir:}") String importDir) {
        this.publisher = publisher;
        this.registry = registry;
        this.library = library;
        this.connections = connections;
        this.store = store;
        this.packages = packages;
        this.configDir = Path.of(configDir);
        this.importDir = importDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAll() throws IOException {
        for (Path file : jsonFiles("entities")) {
            try {
                library.put(Json.MAPPER.readValue(file.toFile(), PayloadDefinition.class));
            } catch (Exception e) {
                log.error("Skipped broken payload definition {}: {}", file, e.getMessage());
            }
        }
        Map<String, String> toActivate = importPackages();
        moveBaseUrlsToConnections();
        for (Path file : jsonFiles("services")) {
            String slug = file.getFileName().toString().replaceFirst("\\.json$", "");
            try {
                if (publisher.loadActive(slug)) {
                    log.info("Serving {} {}", slug, registry.activeVersions().get(slug));
                } else {
                    log.info("{} is a draft that was never published; not served", slug);
                }
            } catch (Exception e) {
                registry.markBroken(slug, e.getMessage());
                log.error("Skipped broken integration {} ({}): {}", slug, file, e.getMessage());
            }
        }
        toActivate.forEach((slug, version) -> {
            try {
                publisher.activateVersion(slug, version);
                log.info("Serving imported {} {}", slug, version);
            } catch (Exception e) {
                registry.markBroken(slug, "Imported version " + version + " could not go live: " + e.getMessage());
                log.error("Imported {} {} could not go live: {}", slug, version, e.getMessage());
            }
        });
    }

    /**
     * Imports every package in the import folder (D-31). Returns, per integration that has no active version here,
     * the version that was live where its package came from; a later package wins for the same integration.
     */
    private Map<String, String> importPackages() throws IOException {
        Map<String, String> toActivate = new LinkedHashMap<>();
        if (importDir == null || importDir.isBlank()) {
            return toActivate;
        }
        Path dir = Path.of(importDir);
        if (!Files.isDirectory(dir)) {
            log.error("Import folder {} does not exist; nothing imported", dir.toAbsolutePath());
            return toActivate;
        }
        List<Path> zips;
        try (Stream<Path> s = Files.list(dir)) {
            zips = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".zip") && Files.isRegularFile(p))
                    .sorted().toList();
        }
        for (Path zip : zips) {
            try {
                Packages.ImportReport report = packages.importZip(Files.readAllBytes(zip));
                log.info("Imported package {}: {} integration(s)", zip.getFileName(), report.integrations().size());
                for (Packages.ImportedIntegration i : report.integrations()) {
                    if (i.exportedActiveVersion() != null && activeVersion(i.id()) == null) {
                        toActivate.put(i.id(), i.exportedActiveVersion());
                    }
                }
            } catch (Packages.PackageException e) {
                log.error("Package {} not imported: {}", zip.getFileName(), String.join("; ", e.problems));
            } catch (Exception e) {
                log.error("Package {} not imported: {}", zip.getFileName(), e.getMessage());
            }
        }
        return toActivate;
    }

    private String activeVersion(String slug) throws IOException {
        Path file = store.service(slug);
        return Files.isRegularFile(file) ? Json.MAPPER.readTree(file.toFile()).path("activeVersion").asText(null) : null;
    }

    /**
     * Drafts from before connections (D-26) that came from a Swagger configuration: the base URL moves into that
     * configuration's connection (when it has none yet) and the draft names the connection instead. Version files
     * are never changed; their literal base URL keeps working.
     */
    private void moveBaseUrlsToConnections() throws IOException {
        for (Path file : jsonFiles("services")) {
            try {
                ObjectNode d = (ObjectNode) Json.MAPPER.readTree(file.toFile());
                String contract = d.path("contract").asText(null);
                if (contract == null || !d.path("target").isObject() || d.path("target").hasNonNull("connection")) {
                    continue;
                }
                ObjectNode target = (ObjectNode) d.get("target");
                String url = target.path("baseUrl").asText("");
                if (!url.isBlank() && connections.baseUrl(contract) == null) {
                    connections.save(contract, url);
                } else if (!url.isBlank() && !url.equals(connections.baseUrl(contract))) {
                    log.warn("{}: base URL {} differs from connection {} ({}); the connection's is used from the next publish",
                            file.getFileName(), url, contract, connections.baseUrl(contract));
                }
                target.put("connection", contract);
                target.remove("baseUrl");
                store.writeJson(file, d);
                log.info("{} now calls through connection {}", file.getFileName(), contract);
            } catch (Exception e) {
                log.error("Could not move the base URL of {} into a connection: {}", file, e.getMessage());
            }
        }
    }

    private List<Path> jsonFiles(String folder) throws IOException {
        Path dir = configDir.resolve(folder);
        if (!Files.isDirectory(dir)) {
            log.info("No {} folder at {}", folder, dir.toAbsolutePath());
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
