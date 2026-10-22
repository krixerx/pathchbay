package integrationmapper.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * Export and import of integrations between environments (D-28, FR-060, journey J3). A package is a ZIP laid out
 * like the config folder: per integration its draft and every version file, plus the payload definitions and Swagger
 * configurations its draft uses, and {@code manifest.json}. Connections, secrets and connection headers never go in:
 * they belong to their environment.
 * <p>
 * Import checks everything before writing anything. Version files are immutable: one that exists with other content
 * refuses the whole import. Imported versions are stored, not activated; each environment makes them live itself.
 */
@Component
public class Packages {

    static final String FORMAT = "patchbay-package";
    private static final String SLUG = "[a-z0-9]+(?:-[a-z0-9]+)*";
    private static final Pattern SERVICE = Pattern.compile("services/(" + SLUG + ")\\.json");
    private static final Pattern VERSION = Pattern.compile("services/(" + SLUG + ")\\.versions/(\\d+\\.\\d+\\.\\d+)\\.json");
    private static final Pattern ENTITY = Pattern.compile("entities/(" + SLUG + ")\\.json");
    private static final Pattern CONTRACT = Pattern.compile("contracts/(" + SLUG + ")\\.json");
    private static final Pattern CONTRACT_SOURCE = Pattern.compile("contracts/(" + SLUG + ")\\.openapi\\.(?:json|yaml|yml)");
    private static final int MAX_ENTRIES = 2000;
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    private final ConfigStore store;
    private final PayloadLibrary library;
    private final IntegrationRegistry registry;
    private final Connections connections;
    private final SecretResolver secrets;

    public Packages(ConfigStore store, PayloadLibrary library, IntegrationRegistry registry, Connections connections,
            SecretResolver secrets) {
        this.store = store;
        this.library = library;
        this.registry = registry;
        this.connections = connections;
        this.secrets = secrets;
    }

    // ---- export

    /** The package of the given integrations, as ZIP bytes. */
    public byte[] export(List<String> slugs) throws IOException {
        if (slugs == null || slugs.isEmpty()) {
            throw new IllegalArgumentException("Pick at least one integration to export");
        }
        Map<String, byte[]> files = new TreeMap<>();
        List<Map<String, Object>> listed = new ArrayList<>();
        Set<String> entities = new LinkedHashSet<>();
        Set<String> contracts = new LinkedHashSet<>();
        for (String slug : new LinkedHashSet<>(slugs)) {
            if (!slug.matches(SLUG) || !Files.isRegularFile(store.service(slug))) {
                throw new IllegalArgumentException("No integration " + slug);
            }
            ObjectNode draft = (ObjectNode) Json.MAPPER.readTree(store.service(slug).toFile());
            String active = draft.path("activeVersion").asText(null);
            // Which version is live is each environment's own business; the manifest says what it was here.
            draft.remove("activeVersion");
            files.put("services/" + slug + ".json", pretty(draft));
            List<String> versions = store.versions(slug);
            for (String v : versions) {
                files.put("services/" + slug + ".versions/" + v + ".json", Files.readAllBytes(store.version(slug, v)));
            }
            for (String field : List.of("input", "output")) {
                if (draft.hasNonNull(field)) {
                    entities.add(draft.get(field).asText());
                }
            }
            if (draft.hasNonNull("contract")) {
                contracts.add(draft.get("contract").asText());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", slug);
            item.put("name", draft.path("name").asText(slug));
            item.put("activeVersion", active);
            item.put("versions", versions);
            listed.add(item);
        }
        for (String id : entities) {
            files.put("entities/" + id + ".json", pretty(library.get(id)));
        }
        for (String id : contracts) {
            Path file = store.root().resolve("contracts").resolve(id + ".json");
            if (!Files.isRegularFile(file)) {
                continue; // the draft names a Swagger configuration deleted since; the versions don't need it
            }
            files.put("contracts/" + id + ".json", Files.readAllBytes(file));
            Contract c = Json.MAPPER.readValue(file.toFile(), Contract.class);
            Path source = c.sourceFile() == null ? null : store.root().resolve("contracts").resolve(c.sourceFile());
            if (source != null && Files.isRegularFile(source)) {
                files.put("contracts/" + c.sourceFile(), Files.readAllBytes(source));
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("formatVersion", 1);
        manifest.put("exportedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        manifest.put("integrations", listed);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "manifest.json", pretty(manifest));
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                put(zip, f.getKey(), f.getValue());
            }
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] pretty(Object value) throws IOException {
        return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
    }

    // ---- import

    /**
     * What an import did for one integration. {@code needs}: what this environment still has to set before it can go
     * live. {@code warnings}: versions published before connections (D-26), which call their old address literally.
     */
    public record ImportedIntegration(String id, String name, List<String> versionsAdded, List<String> versionsAlreadyHere,
            String exportedActiveVersion, String liveVersion, List<String> needs, List<String> warnings) {
    }

    public record ImportReport(String exportedAt, List<ImportedIntegration> integrations, List<String> definitions,
            List<String> contracts) {
    }

    /** Reads, checks and then writes a package. Throws, with every problem found, before writing anything. */
    public synchronized ImportReport importZip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> files = unzip(zipBytes);
        byte[] manifestBytes = files.remove("manifest.json");
        if (manifestBytes == null) {
            throw new IllegalArgumentException("Not a Patchbay package: manifest.json is missing");
        }
        JsonNode manifest = Json.MAPPER.readTree(manifestBytes);
        if (!FORMAT.equals(manifest.path("format").asText()) || manifest.path("formatVersion").asInt() != 1) {
            throw new IllegalArgumentException("Not a Patchbay package this engine can read (format "
                    + manifest.path("format").asText("?") + " " + manifest.path("formatVersion").asText("?") + ")");
        }

        // 1. Sort and check every file; nothing is written yet.
        List<String> problems = new ArrayList<>();
        Map<String, ObjectNode> drafts = new TreeMap<>();
        Map<String, Map<String, byte[]>> versions = new TreeMap<>();
        Map<String, PayloadDefinition> definitions = new TreeMap<>();
        Map<String, byte[]> contractFiles = new TreeMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String name = f.getKey();
            Matcher m;
            try {
                if ((m = SERVICE.matcher(name)).matches()) {
                    ObjectNode d = (ObjectNode) Json.MAPPER.readTree(f.getValue());
                    Json.MAPPER.treeToValue(d, ServiceDefinition.class);
                    if (!m.group(1).equals(d.path("id").asText())) {
                        problems.add(name + ": its id is " + d.path("id").asText("missing"));
                    }
                    drafts.put(m.group(1), d);
                } else if ((m = VERSION.matcher(name)).matches()) {
                    checkVersion(m.group(1), m.group(2), f.getValue());
                    versions.computeIfAbsent(m.group(1), k -> new TreeMap<>()).put(m.group(2), f.getValue());
                } else if ((m = ENTITY.matcher(name)).matches()) {
                    PayloadDefinition def = Json.MAPPER.readValue(f.getValue(), PayloadDefinition.class);
                    def.check();
                    if (!m.group(1).equals(def.id())) {
                        problems.add(name + ": its id is " + def.id());
                    }
                    definitions.put(def.id(), def);
                } else if ((m = CONTRACT.matcher(name)).matches()) {
                    Contract c = Json.MAPPER.readValue(f.getValue(), Contract.class);
                    if (!m.group(1).equals(c.id())) {
                        problems.add(name + ": its id is " + c.id());
                    } else if (c.sourceFile() != null && !files.containsKey("contracts/" + c.sourceFile())) {
                        problems.add(name + ": its original file " + c.sourceFile() + " is missing");
                    }
                    contractFiles.put(name, f.getValue());
                } else if (CONTRACT_SOURCE.matcher(name).matches()) {
                    contractFiles.put(name, f.getValue());
                } else {
                    problems.add(name + ": not a file a package holds");
                }
            } catch (IOException | RuntimeException e) {
                problems.add(name + ": " + e.getMessage());
            }
        }
        for (String slug : versions.keySet()) {
            if (!drafts.containsKey(slug) && !Files.isRegularFile(store.service(slug))) {
                problems.add("Integration " + slug + " has versions but no services/" + slug + ".json");
            }
        }
        for (Map.Entry<String, ObjectNode> d : drafts.entrySet()) {
            for (String field : List.of("input", "output")) {
                String id = d.getValue().path(field).asText(null);
                if (id != null && !definitions.containsKey(id) && !known(id)) {
                    problems.add("Integration " + d.getKey() + " uses payload definition " + id + ", which is neither in the package nor here");
                }
            }
        }
        // Published versions never change: the same number with other content is a different version.
        Map<String, List<String>> added = new TreeMap<>();
        Map<String, List<String>> alreadyHere = new TreeMap<>();
        for (Map.Entry<String, Map<String, byte[]>> e : versions.entrySet()) {
            for (Map.Entry<String, byte[]> v : e.getValue().entrySet()) {
                Path here = store.version(e.getKey(), v.getKey());
                if (!Files.isRegularFile(here)) {
                    added.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(v.getKey());
                } else if (Json.MAPPER.readTree(here.toFile()).equals(Json.MAPPER.readTree(v.getValue()))) {
                    alreadyHere.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(v.getKey());
                } else {
                    problems.add("Integration " + e.getKey() + " already has a different version " + v.getKey()
                            + " here; published versions never change, so publish a new version where the package came from");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new PackageException(problems);
        }

        // 2. Write: library first, then contracts, versions and drafts, so a draft never names something missing.
        for (PayloadDefinition def : definitions.values()) {
            store.writeJson(store.root().resolve("entities").resolve(def.id() + ".json"), def);
            library.put(def);
        }
        for (Map.Entry<String, byte[]> c : contractFiles.entrySet()) {
            byte[] content = c.getValue();
            store.write(store.root().resolve(c.getKey()), out -> out.write(content));
        }
        for (Map.Entry<String, List<String>> e : added.entrySet()) {
            for (String v : e.getValue()) {
                byte[] content = versions.get(e.getKey()).get(v);
                store.write(store.version(e.getKey(), v), out -> out.write(content));
            }
        }
        for (Map.Entry<String, ObjectNode> d : drafts.entrySet()) {
            ObjectNode draft = d.getValue();
            String live = activeHere(d.getKey());
            draft.remove("activeVersion");
            if (live != null) {
                draft.put("activeVersion", live);
            }
            store.writeJson(store.service(d.getKey()), draft);
        }

        // 3. Report, with what each integration still needs in this environment before it can go live.
        Map<String, JsonNode> listed = new LinkedHashMap<>();
        manifest.path("integrations").forEach(i -> listed.put(i.path("id").asText(), i));
        Set<String> slugs = new LinkedHashSet<>(listed.keySet());
        slugs.addAll(drafts.keySet());
        slugs.addAll(versions.keySet());
        List<ImportedIntegration> report = new ArrayList<>();
        for (String slug : slugs) {
            JsonNode item = listed.getOrDefault(slug, Json.MAPPER.createObjectNode());
            ObjectNode draft = drafts.get(slug);
            report.add(new ImportedIntegration(slug, draft == null ? slug : draft.path("name").asText(slug),
                    added.getOrDefault(slug, List.of()), alreadyHere.getOrDefault(slug, List.of()),
                    item.path("activeVersion").asText(null), registry.activeVersions().get(slug), needs(slug),
                    literalAddresses(versions.getOrDefault(slug, Map.of()))));
        }
        List<String> contractIds = contractFiles.keySet().stream().map(CONTRACT::matcher).filter(Matcher::matches)
                .map(m -> m.group(1)).toList();
        return new ImportReport(manifest.path("exportedAt").asText(null), report, List.copyOf(definitions.keySet()), contractIds);
    }

    private static List<String> literalAddresses(Map<String, byte[]> versions) throws IOException {
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, byte[]> v : versions.entrySet()) {
            ServiceDefinition.Target t = Json.MAPPER.readValue(v.getValue(), VersionSnapshot.class).service().target();
            if (t.connection() == null && t.baseUrl() != null) {
                warnings.add("Version " + v.getKey() + " was published before connections and calls " + t.baseUrl()
                        + " in every environment; publish it again where it came from to use this environment's connection");
            }
        }
        return warnings;
    }

    private boolean known(String definitionId) {
        try {
            library.get(definitionId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void checkVersion(String slug, String version, byte[] content) throws IOException {
        Version.parse(version);
        VersionSnapshot s = Json.MAPPER.readValue(content, VersionSnapshot.class);
        if (!version.equals(s.version()) || s.service() == null || !slug.equals(s.service().id())) {
            throw new IllegalArgumentException("it is not version " + version + " of " + slug);
        }
        s.input().check();
        s.output().check();
        CompiledIntegration.compile(s.service(), s.input(), s.output(), null, "http://localhost");
    }

    private String activeHere(String slug) throws IOException {
        if (!Files.isRegularFile(store.service(slug))) {
            return null;
        }
        return Json.MAPPER.readTree(store.service(slug).toFile()).path("activeVersion").asText(null);
    }

    /** What this environment must set before the integration's versions can go live: connection and secrets. */
    private List<String> needs(String slug) throws IOException {
        ServiceDefinition def = Json.MAPPER.readValue(store.service(slug).toFile(), ServiceDefinition.class);
        List<String> needs = new ArrayList<>();
        String connection = def.target() == null ? null : def.target().connection();
        if (connection != null) {
            if (connections.baseUrl(connection) == null) {
                needs.add("A base URL for connection " + connection + " (Configuration, or " + Connections.envName(connection) + ")");
            }
            try {
                connections.headers(connection);
            } catch (IllegalArgumentException e) {
                needs.add(e.getMessage());
            }
        }
        if (def.auth() != null && !secrets.inEnv(slug) && !secrets.inFile(slug)) {
            needs.add("Its secret (Settings, or " + SecretResolver.envName(slug) + ")");
        }
        return needs;
    }

    /** Reads the ZIP into memory, refusing anything outside the package layout or too large. */
    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) {
                    continue;
                }
                if (files.size() == MAX_ENTRIES) {
                    throw new IllegalArgumentException("The package has more than " + MAX_ENTRIES + " files");
                }
                byte[] content = readLimited(zip, MAX_BYTES - total);
                total += content.length;
                if (files.put(e.getName(), content) != null) {
                    throw new IllegalArgumentException("The package has " + e.getName() + " twice");
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("Not a ZIP file: " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Not a Patchbay package: the file is empty or not a ZIP");
        }
        return files;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                throw new IllegalArgumentException("The package is larger than " + MAX_BYTES / (1024 * 1024) + " MB unpacked");
            }
        }
        return out.toByteArray();
    }

    /** An import refused before anything was written, with every problem found. */
    public static final class PackageException extends IllegalArgumentException {
        final List<String> problems;

        PackageException(List<String> problems) {
            super("Nothing was imported: " + problems.size() + (problems.size() == 1 ? " problem" : " problems"));
            this.problems = List.copyOf(problems);
        }
    }
}
