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
 * Publishes every {@code <config-dir>/services/*.json} at startup. A broken file is skipped and reported,
 * the others still start (R10).
 */
@Component
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private final IntegrationPublisher publisher;
    private final IntegrationRegistry registry;
    private final Path configDir;

    public ConfigLoader(IntegrationPublisher publisher, IntegrationRegistry registry,
            @Value("${mapper.config-dir:config}") String configDir) {
        this.publisher = publisher;
        this.registry = registry;
        this.configDir = Path.of(configDir);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAll() throws IOException {
        Path services = configDir.resolve("services");
        if (!Files.isDirectory(services)) {
            log.info("No services folder at {}; nothing to publish", services.toAbsolutePath());
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(services)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String slug = file.getFileName().toString().replaceFirst("\\.json$", "");
            try {
                ServiceDefinition def = Json.MAPPER.readValue(file.toFile(), ServiceDefinition.class);
                publisher.publish(def);
                log.info("Published {} {} → {} {}{}", def.id(), def.version(), def.target().method(),
                        def.target().baseUrl(), def.target().path());
            } catch (Exception e) {
                registry.markBroken(slug, e.getMessage());
                log.error("Skipped broken integration {} ({}): {}", slug, file, e.getMessage());
            }
        }
    }
}
