package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Finds the outbound secret of an integration. The environment always wins (spec FR-052): variable
 * {@code MAPPER_SECRET_<SLUG>} (slug upper-cased, other characters as {@code _}). Otherwise
 * {@code <config-dir>/secrets/<slug>.json} with {"value": "..."}, the file the console will write.
 * Secrets never go into service or version files.
 */
@Component
public class SecretResolver {

    private final Environment env;
    private final ConfigStore store;

    public SecretResolver(Environment env, ConfigStore store) {
        this.env = env;
        this.store = store;
    }

    public static String envName(String slug) {
        return "MAPPER_SECRET_" + slug.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** Whether the environment variable is set; the console shows where a secret comes from, never its value. */
    public boolean inEnv(String slug) {
        String v = env.getProperty(envName(slug));
        return v != null && !v.isBlank();
    }

    public boolean inFile(String slug) {
        return Files.isRegularFile(store.secret(slug));
    }

    /** Saves a secret typed in the console (D-3); an empty value removes the file. */
    public void save(String slug, String value) throws IOException {
        Path file = store.secret(slug);
        if (value == null || value.isBlank()) {
            Files.deleteIfExists(file);
            return;
        }
        writePrivate(store, file, java.util.Map.of("value", value));
    }

    /**
     * Writes a file under {@code <config-dir>/secrets}. Where the file system has POSIX permissions (the Docker
     * image), its folders and the file are readable only by the engine's user.
     */
    static void writePrivate(ConfigStore store, Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        boolean posix = file.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Path secrets = store.root().resolve("secrets");
            for (Path dir = file.getParent(); dir != null && dir.startsWith(secrets); dir = dir.getParent()) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
        }
        store.writeJson(file, value);
        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /** The secret, or throws naming both places it was looked for. */
    public String resolve(String slug) {
        String fromEnv = env.getProperty(envName(slug));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path file = store.secret(slug);
        if (Files.isRegularFile(file)) {
            try {
                String value = Json.MAPPER.readTree(file.toFile()).path("value").asText("");
                if (!value.isBlank()) {
                    return value;
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Secret file " + file + " is not valid JSON: " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("Integration " + slug + " needs a secret: set " + envName(slug)
                + " or write {\"value\": \"...\"} to " + file);
    }
}
