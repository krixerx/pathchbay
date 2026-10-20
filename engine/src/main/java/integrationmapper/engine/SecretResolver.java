package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
