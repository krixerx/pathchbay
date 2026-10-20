package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup loads the payload library ({@code <config-dir>/entities/*.json}), then publishes every
 * {@code <config-dir>/services/*.json}. A broken file is skipped and reported, the others still start (R10).
 */
@Component
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private final IntegrationPublisher publisher;
    private final IntegrationRegistry registry;
    private final PayloadLibrary library;
    private final Path configDir;

    public ConfigLoader(IntegrationPublisher publisher, IntegrationRegistry registry, PayloadLibrary library,
            @Value("${mapper.config-dir:config}") String configDir) {
        this.publisher = publisher;
        this.registry = registry;
        this.library = library;
        this.configDir = Path.of(configDir);
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
        for (Path file : jsonFiles("services")) {
            String slug = file.getFileName().toString().replaceFirst("\\.json$", "");
            try {
                ServiceDefinition def = Json.MAPPER.readValue(file.toFile(), ServiceDefinition.class);
                publisher.publish(def);
                log.info("Published {} {} ({} → {}) → {} {}{}", def.id(), def.version(), def.input(), def.output(),
                        def.target().method(), def.target().baseUrl(), def.target().path());
            } catch (Exception e) {
                registry.markBroken(slug, e.getMessage());
                log.error("Skipped broken integration {} ({}): {}", slug, file, e.getMessage());
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
