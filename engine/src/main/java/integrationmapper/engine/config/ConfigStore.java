package integrationmapper.engine.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import integrationmapper.engine.format.Json;
import integrationmapper.engine.model.Version;

/**
 * Reads and writes the config folder. Every write is crash-safe (R4): the new content goes to a temp file in the
 * same folder, which then replaces the target in one rename, so a file is either old or new, never half. Each write
 * has its own temp file, so two writes of one file at the same time can't mix their content. A rename
 * blocked by another process (a Windows file lock, Docker Desktop shares) is retried before giving up (OV-11).
 */
@Component
public class ConfigStore {

    private static final int MOVE_ATTEMPTS = 4;
    private static final long RETRY_PAUSE_MS = 333;

    private final Path root;

    @Autowired
    public ConfigStore(MapperProperties properties) {
        this(properties.configDir());
    }

    /** For tests and tools that work on a folder of their own. */
    public ConfigStore(String configDir) {
        this.root = Path.of(configDir);
    }

    public Path root() {
        return root;
    }

    public Path service(String slug) {
        return root.resolve("services").resolve(slug + ".json");
    }

    public Path versionsDir(String slug) {
        return root.resolve("services").resolve(slug + ".versions");
    }

    public Path version(String slug, String version) {
        return versionsDir(slug).resolve(version + ".json");
    }

    public Path secret(String slug) {
        return root.resolve("secrets").resolve(slug + ".json");
    }

    /** A connection's base URL in this environment (D-26); like secrets, it is not moved between environments. */
    public Path connection(String id) {
        return root.resolve("connections").resolve(id + ".json");
    }

    /** The secret header values of a connection (D-27), kept with the other secrets. */
    public Path connectionSecrets(String id) {
        return root.resolve("secrets").resolve("connections").resolve(id + ".json");
    }

    /** The versions stored for one integration, oldest first. */
    public List<String> versions(String slug) throws IOException {
        Path dir = versionsDir(slug);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<String> versions = new ArrayList<>(s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).toList());
            versions.sort(Comparator.comparing(Version::parse));
            return versions;
        }
    }

    public void writeJson(Path target, Object value) throws IOException {
        write(target, out -> Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, value));
    }

    /** Writes through a temp file in the target's folder; on any failure the target keeps its old content. */
    public void write(Path target, IoWriter writer) throws IOException {
        Files.createDirectories(target.getParent());
        // A unique name opened with CREATE_NEW keeps the folder's default permissions (createTempFile would make it 0600).
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(out);
            }
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                if (attempt == MOVE_ATTEMPTS) {
                    throw new IOException("Could not save " + target + " after " + attempt + " attempts: " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(RETRY_PAUSE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @FunctionalInterface
    public interface IoWriter {
        void write(OutputStream out) throws IOException;
    }
}
