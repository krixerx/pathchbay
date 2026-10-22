package integrationmapper.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Base URLs per connection (D-26). A connection belongs to a Swagger configuration and has its id; every integration
 * calling that external system uses it, so the address is set once per environment and never travels with
 * integrations or versions. The environment wins: variable {@code MAPPER_URL_<ID>} (id upper-cased, other
 * characters as {@code _}). Otherwise {@code <config-dir>/connections/<id>.json} with {"baseUrl": "..."}, written
 * by the console.
 * <p>
 * A connection can also carry headers sent on every call to its system (D-27), such as a client id: a name, and a
 * value per environment from variable {@code MAPPER_HEADER_<ID>_<NAME>} or else the connection file. A secret
 * header's value is kept in {@code <config-dir>/secrets/connections/<id>.json}, never in the connection file, and
 * shows as *** in the console, the Test drawer and the call log.
 */
@Component
public class Connections {

    /** Headers the engine sets itself; neither a connection nor the board can send them (D-27). */
    static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "content-type", "transfer-encoding", "connection");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+^_`|~-]+");
    private static final TypeReference<List<HeaderEntry>> HEADER_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> SECRET_MAP = new TypeReference<>() {
    };

    /** A header as the connection file keeps it; a secret one has no value there. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record HeaderEntry(String name, String value, Boolean secret) {

        boolean isSecret() {
            return Boolean.TRUE.equals(secret);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ConnectionFile(String baseUrl, List<HeaderEntry> headers) {
    }

    /** A header ready to send, with its value in this environment. */
    public record Header(String name, String value, boolean secret) {
    }

    /** A header as the console sends it; a secret one without a value keeps the value stored before. */
    public record HeaderUpdate(String name, String value, boolean secret) {
    }

    /**
     * A header as the console shows it: {@code value} only when it is not secret, {@code saved} whether the engine
     * has a value of its own (the variable aside).
     */
    public record HeaderSetting(String name, boolean secret, String value, boolean saved, String envName, boolean inEnv) {
    }

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
        return read(id).baseUrl();
    }

    private ConnectionFile read(String id) {
        Path file = store.connection(id);
        if (!Files.isRegularFile(file)) {
            return new ConnectionFile(null, List.of());
        }
        try {
            JsonNode n = Json.MAPPER.readTree(file.toFile());
            String url = n.path("baseUrl").asText("");
            List<HeaderEntry> headers = n.has("headers") ? Json.MAPPER.convertValue(n.get("headers"), HEADER_LIST) : List.of();
            return new ConnectionFile(url.isBlank() ? null : url, headers);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Connection file " + file + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    private Map<String, String> savedSecrets(String id) {
        Path file = store.connectionSecrets(id);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return Json.MAPPER.readValue(file.toFile(), SECRET_MAP);
        } catch (IOException e) {
            throw new IllegalArgumentException("Secret file " + file + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** The base URL in this environment, or null when neither place has one. */
    public String baseUrl(String id) {
        String v = fromEnv(id);
        return v != null ? v : fromFile(id);
    }

    /** Saves the URL typed in the console, keeping the headers; empty removes the URL. */
    public void save(String id, String url) throws IOException {
        save(id, url, null);
    }

    /**
     * Saves the console's connection form: the base URL (empty removes it) and, unless {@code headers} is null, the
     * headers in their order. Every header needs a value from the form, the one stored before (secret headers) or
     * the environment; otherwise nothing is saved.
     */
    public void save(String id, String url, List<HeaderUpdate> headers) throws IOException {
        String v = url == null ? "" : url.strip();
        if (!v.isEmpty()) {
            check(v);
        }
        List<HeaderEntry> entries = read(id).headers();
        Map<String, String> secrets = null;
        if (headers != null) {
            Map<String, String> stored = savedSecrets(id);
            entries = new ArrayList<>();
            secrets = new LinkedHashMap<>();
            Set<String> seen = new HashSet<>();
            for (HeaderUpdate h : headers) {
                String name = h.name() == null ? "" : h.name().strip();
                checkHeaderName(name);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Header " + name + " is listed twice");
                }
                String value = h.value() == null ? "" : h.value().strip();
                checkHeaderValue(name, value);
                String kept = !value.isEmpty() ? value : h.secret() ? stored.get(name) : null;
                if (kept == null && fromEnv(id, name) == null) {
                    throw new IllegalArgumentException("Header " + name + " needs a value: type one or set " + envName(id, name));
                }
                if (h.secret()) {
                    if (kept != null) {
                        secrets.put(name, kept);
                    }
                    entries.add(new HeaderEntry(name, null, true));
                } else {
                    entries.add(new HeaderEntry(name, kept, null));
                }
            }
        }
        Path file = store.connection(id);
        if (v.isEmpty() && entries.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            store.writeJson(file, new ConnectionFile(v.isEmpty() ? null : v, entries.isEmpty() ? null : entries));
        }
        if (secrets != null) {
            if (secrets.isEmpty()) {
                Files.deleteIfExists(store.connectionSecrets(id));
            } else {
                SecretResolver.writePrivate(store, store.connectionSecrets(id), secrets);
            }
        }
    }

    // ---- headers (D-27)

    public static String envName(String id, String header) {
        return "MAPPER_HEADER_" + (id + "_" + header).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private String fromEnv(String id, String header) {
        String v = env.getProperty(envName(id, header));
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** The headers as the console shows them; secret values never leave the engine. */
    public List<HeaderSetting> headerSettings(String id) {
        Map<String, String> secrets = savedSecrets(id);
        return read(id).headers().stream().map(h -> new HeaderSetting(h.name(), h.isSecret(), h.isSecret() ? null : h.value(),
                h.isSecret() ? secrets.containsKey(h.name()) : h.value() != null && !h.value().isBlank(), envName(id, h.name()),
                fromEnv(id, h.name()) != null)).toList();
    }

    /** The headers a connection sends in this environment; throws naming a header that has no value. */
    public List<Header> headers(String id) {
        List<HeaderEntry> entries = read(id).headers();
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, String> secrets = savedSecrets(id);
        List<Header> out = new ArrayList<>();
        for (HeaderEntry h : entries) {
            String v = fromEnv(id, h.name());
            if (v == null) {
                v = h.isSecret() ? secrets.get(h.name()) : h.value();
            }
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("Header " + h.name() + " of connection " + id
                        + " has no value: set it under Configuration or set " + envName(id, h.name()));
            }
            out.add(new Header(h.name(), v, h.isSecret()));
        }
        return List.copyOf(out);
    }

    /** The headers of the integration's connection; none for files from before connections. */
    public List<Header> headers(ServiceDefinition def) {
        String c = def.target().connection();
        return c == null ? List.of() : headers(c);
    }

    /** A header name a connection or the board may send: an HTTP token without dots (links use dotted paths). */
    static void checkHeaderName(String name) {
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a header name: use letters, digits and -, no spaces or dots");
        }
        if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Header " + name + " is set by the engine itself");
        }
    }

    private static void checkHeaderValue(String name, String value) {
        if (value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f)) {
            throw new IllegalArgumentException("The value of header " + name + " has line breaks or other control characters");
        }
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
