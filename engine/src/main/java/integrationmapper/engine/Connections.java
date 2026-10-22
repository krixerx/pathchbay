package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Base URLs per connection (D-26). A connection belongs to a Swagger configuration and has its id; every integration
 * calling that external system uses it, so the address is set once per environment and never travels with
 * integrations or versions. The environment wins: variable {@code MAPPER_URL_<ID>} (id upper-cased, other
 * characters as {@code _}). Otherwise {@code <config-dir>/connections/<id>.json} with {"baseUrl": "..."}, written
 * by the console.
 */
@Component
public class Connections {

    private final Environment env;
    private final ConfigStore store;

    public Connections(Environment env, ConfigStore store) {
        this.env = env;
        this.store = store;
    }

    public static String envName(String id) {
        return "MAPPER_URL_" + id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    public String fromEnv(String id) {
        String v = env.getProperty(envName(id));
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** The URL saved in the connection file, or null. */
    public String fromFile(String id) {
        Path file = store.connection(id);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String v = Json.MAPPER.readTree(file.toFile()).path("baseUrl").asText("");
            return v.isBlank() ? null : v;
        } catch (IOException e) {
            throw new IllegalArgumentException("Connection file " + file + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** The base URL in this environment, or null when neither place has one. */
    public String baseUrl(String id) {
        String v = fromEnv(id);
        return v != null ? v : fromFile(id);
    }

    /** Saves the URL typed in the console; empty removes the file. */
    public void save(String id, String url) throws IOException {
        String v = url == null ? "" : url.strip();
        if (v.isEmpty()) {
            Files.deleteIfExists(store.connection(id));
            return;
        }
        check(v);
        store.writeJson(store.connection(id), Map.of("baseUrl", v));
    }

    static void check(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("The base URL must start with http:// or https://");
        }
    }

    /**
     * The base URL an integration calls: its connection's, or the literal one of files from before connections.
     * Throws naming both places a connection's URL can come from.
     */
    public String resolve(ServiceDefinition def) {
        ServiceDefinition.Target t = def.target();
        if (t.connection() == null) {
            if (t.baseUrl() == null || t.baseUrl().isBlank()) {
                throw new IllegalArgumentException("Integration " + def.id() + " has no connection and no base URL");
            }
            return t.baseUrl();
        }
        String url = baseUrl(t.connection());
        if (url == null) {
            throw new IllegalArgumentException("Connection " + t.connection() + " has no base URL: set it under Configuration or set "
                    + envName(t.connection()));
        }
        return url;
    }
}
