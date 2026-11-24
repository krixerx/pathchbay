package integrationmapper.engine;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import org.springframework.stereotype.Component;

/**
 * Imports an OpenAPI 3 or Swagger 2.0 contract (converted, D-5) and normalizes its operations (spec FR-001, FR-004).
 * Parts that can't be resolved become warnings and the import continues (D-5). The original text is kept unchanged.
 */
@Component
public class ContractImporter {

    private static final int MAX_DEPTH = 12;

    private final ConfigStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** For the schemas a WSDL imports (D-30): no redirects, so a fetch can't leave the contract's host. */
    private final HttpClient noRedirects = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public ContractImporter(ConfigStore store) {
        this.store = store;
    }

    /** Fetches a contract by URL from the engine. An unreachable URL gets the D-5 message: upload the file instead. */
    public String fetch(String url) {
        return fetch(url, http);
    }

    /** Fetches a schema a WSDL imports; a redirect is refused, since it could point to another host. */
    String fetchSchema(String url) {
        return fetch(url, noRedirects);
    }

    private String fetch(String url, HttpClient client) {
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300 && r.statusCode() < 400) {
                throw new IllegalArgumentException("The URL answered " + r.statusCode() + " (a redirect), which is not followed for schemas");
            }
            if (r.statusCode() >= 400) {
                throw new IllegalArgumentException("The URL answered " + r.statusCode() + ". Check the address, or download the file and upload it instead.");
            }
            return r.body();
        } catch (ConnectException | UnknownHostException | HttpTimeoutException e) {
            throw new IllegalArgumentException("The engine could not reach " + url + " (" + e.getClass().getSimpleName()
                    + "). If the file is only reachable from your computer, download it and upload the file instead.");
        } catch (IllegalArgumentException e) {
            throw e.getMessage() != null && e.getMessage().startsWith("The URL") ? e
                    : new IllegalArgumentException("Not a valid URL: " + url);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read " + url + ": " + e.getMessage()
                    + ". Download the file and upload it instead.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parses, normalizes and stores the contract under {@code id}; a re-import replaces the previous one.
     * {@code sourceUrl}: where the text was fetched from, null for an upload.
     */
    public Contract importContract(String id, String name, String text, String sourceUrl) throws IOException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The contract is empty");
        }
        if (WsdlImporter.looksLikeWsdl(text)) {
            SafeXml.Sources sources = sourceUrl == null ? SafeXml.Sources.none() : SafeXml.Sources.ofUrl(sourceUrl, this::fetchSchema);
            return importWsdl(id, name, sourceUrl == null ? "service.wsdl" : sourceUrl, text.getBytes(StandardCharsets.UTF_8), sources,
                    sourceUrl);
        }
        if (text.stripLeading().startsWith("<")) {
            throw new IllegalArgumentException(NOT_A_CONTRACT);
        }
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(text, null, options);
        OpenAPI api = result.getOpenAPI();
        List<String> warnings = new ArrayList<>(result.getMessages() == null ? List.of() : result.getMessages());
        boolean declared = text.contains("openapi") || text.contains("swagger");
        if (api == null || api.getPaths() == null || !declared) {
            throw new IllegalArgumentException(NOT_A_CONTRACT + (warnings.isEmpty() ? "" : ": " + String.join("; ", warnings)));
        }
        boolean swagger2 = text.contains("\"swagger\"") || text.stripLeading().startsWith("swagger:");
        if (swagger2) {
            warnings.add(0, "Swagger 2.0 file converted to OpenAPI 3");
        }

        List<Contract.Operation> operations = new ArrayList<>();
        for (Map.Entry<String, PathItem> p : api.getPaths().entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> o : p.getValue().readOperationsMap().entrySet()) {
                operations.add(operation(p.getKey(), p.getValue(), o.getKey().name(), o.getValue(), warnings));
            }
        }
        List<String> servers = api.getServers() == null ? List.of()
                : api.getServers().stream().map(ContractImporter::serverUrl).filter(s -> s != null && !s.isBlank()).toList();

        boolean json = text.stripLeading().startsWith("{");
        String sourceFile = id + ".openapi." + (json ? "json" : "yaml");
        removeSources(id);
        store.write(store.root().resolve("contracts").resolve(sourceFile), out -> out.write(text.getBytes(StandardCharsets.UTF_8)));
        Contract contract = new Contract(id, name, sourceFile, sourceUrl, OffsetDateTime.now(ZoneOffset.UTC).toString(), servers,
                operations, warnings);
        store.writeJson(store.root().resolve("contracts").resolve(id + ".json"), contract);
        return contract;
    }

    static final String NOT_A_CONTRACT = "Not a contract Patchbay can read: give an OpenAPI 3, Swagger 2.0 or WSDL 1.1 file";
    static final int MAX_ZIP_ENTRIES = 500;
    static final long MAX_ZIP_BYTES = 20L * 1024 * 1024;

    /**
     * Imports a WSDL contract from a ZIP holding the WSDL and the XSD files it imports (D-30). The WSDL is the one
     * {@code .wsdl} file nearest the top of the ZIP.
     */
    public Contract importZip(String id, String name, byte[] zip) throws IOException {
        Map<String, byte[]> entries = unzip(zip);
        int best = Integer.MAX_VALUE;
        List<String> candidates = new ArrayList<>();
        for (String path : entries.keySet()) {
            if (path.toLowerCase().endsWith(".wsdl")) {
                int depth = path.split("/").length;
                if (depth < best) {
                    best = depth;
                    candidates.clear();
                }
                if (depth == best) {
                    candidates.add(path);
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("The ZIP has no .wsdl file");
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("The ZIP has several WSDL files at the same level (" + String.join(", ", candidates)
                    + "); keep only the service's main WSDL there");
        }
        String main = candidates.get(0);
        return importWsdl(id, name, main, entries.get(main), SafeXml.Sources.ofZip(entries), null);
    }

    /** The files of a ZIP by path; refuses absolute paths, {@code ..}, and more than the size limits. */
    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory()) {
                    continue;
                }
                String path = e.getName().replace('\\', '/');
                if (path.startsWith("/") || path.matches("^[A-Za-z]:.*") || List.of(path.split("/")).contains("..")) {
                    throw new IllegalArgumentException("The ZIP entry " + e.getName() + " has a path outside the ZIP");
                }
                if (out.size() >= MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("The ZIP has more than " + MAX_ZIP_ENTRIES + " files");
                }
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_ZIP_BYTES) {
                        throw new IllegalArgumentException("The ZIP holds more than " + MAX_ZIP_BYTES / 1024 / 1024 + " MB");
                    }
                    bytes.write(buf, 0, n);
                }
                out.put(path, bytes.toByteArray());
            }
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("Not a valid ZIP: " + e.getMessage());
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The ZIP is empty or not a ZIP");
        }
        return out;
    }

    /**
     * Reads the WSDL, then stores every file it used in {@code contracts/<id>.wsdl/} (replacing what was there) and
     * the normalized contract. {@code mainKey}: the WSDL's path in the ZIP or its URL.
     */
    private Contract importWsdl(String id, String name, String mainKey, byte[] main, SafeXml.Sources sources, String sourceUrl)
            throws IOException {
        WsdlImporter.Result r = WsdlImporter.read(mainKey, main, sources);
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, byte[]> stored = new LinkedHashMap<>();
        String sourceFile = null;
        for (Map.Entry<String, byte[]> e : sources.used().entrySet()) {
            String file = sources.isZip() ? e.getKey() : uniqueName(fileName(e.getKey()), stored.keySet());
            stored.put(file, e.getValue());
            files.put(file, e.getKey());
            if (e.getKey().equals(mainKey)) {
                sourceFile = id + WSDL_DIR + "/" + file;
            }
        }
        removeSources(id);
        java.nio.file.Path dir = store.root().resolve("contracts").resolve(id + WSDL_DIR);
        for (Map.Entry<String, byte[]> e : stored.entrySet()) {
            java.nio.file.Path target = dir.resolve(e.getKey()).normalize();
            if (!target.startsWith(dir)) {
                throw new IllegalArgumentException("The file " + e.getKey() + " has a path outside the contract");
            }
            java.nio.file.Files.createDirectories(target.getParent());
            store.write(target, out -> out.write(e.getValue()));
        }
        Contract contract = new Contract(id, name, sourceFile, sourceUrl, OffsetDateTime.now(ZoneOffset.UTC).toString(), r.servers(),
                r.operations(), r.warnings(), Contract.WSDL, files);
        store.writeJson(store.root().resolve("contracts").resolve(id + ".json"), contract);
        return contract;
    }

    /** The folder suffix of a WSDL contract's original files. */
    static final String WSDL_DIR = ".wsdl";

    /** Removes the original files of contract {@code id}: {@code <id>.openapi.*} and the {@code <id>.wsdl/} folder. */
    void removeSources(String id) throws IOException {
        java.nio.file.Path dir = store.root().resolve("contracts");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(id + ".openapi.")) {
                    java.nio.file.Files.delete(p);
                } else if (n.equals(id + WSDL_DIR) && java.nio.file.Files.isDirectory(p)) {
                    deleteTree(p);
                }
            }
        }
    }

    static void deleteTree(java.nio.file.Path dir) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(p);
            }
        }
    }

    /** A file name for a fetched URL: its last path segment plus its query, e.g. {@code person_xsd=1.xsd}. */
    static String fileName(String url) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "file.xml";
        }
        String path = u.getPath() == null ? "" : u.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        String query = u.getQuery();
        String n = (last.isEmpty() ? "service" : last) + (query == null ? "" : "_" + query);
        n = n.replaceAll("[^A-Za-z0-9._=-]", "_");
        if (query != null) {
            n += query.toLowerCase().startsWith("wsdl") ? ".wsdl" : ".xsd";
        }
        return n;
    }

    private static String uniqueName(String base, java.util.Set<String> taken) {
        String n = base;
        for (int i = 2; taken.contains(n); i++) {
            int dot = base.lastIndexOf('.');
            n = dot < 0 ? base + "-" + i : base.substring(0, dot) + "-" + i + base.substring(dot);
        }
        return n;
    }

    /** Server URL with its variables filled with their defaults (base URL rule). */
    private static String serverUrl(Server s) {
        String url = s.getUrl();
        if (url != null && s.getVariables() != null) {
            for (var v : s.getVariables().entrySet()) {
                url = url.replace("{" + v.getKey() + "}", v.getValue().getDefault() == null ? "" : v.getValue().getDefault());
            }
        }
        return url;
    }

    private Contract.Operation operation(String path, PathItem item, String method, io.swagger.v3.oas.models.Operation op, List<String> warnings) {
        String label = method + " " + path;
        List<Contract.Parameter> params = new ArrayList<>();
        List<Parameter> all = new ArrayList<>();
        if (item.getParameters() != null) {
            all.addAll(item.getParameters());
        }
        if (op.getParameters() != null) {
            all.addAll(op.getParameters());
        }
        for (Parameter p : all) {
            if (List.of("path", "query", "header").contains(p.getIn())) {
                PayloadDefinition.Field f = field(p.getName(), p.getSchema(), Boolean.TRUE.equals(p.getRequired()), label, warnings,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                params.add(new Contract.Parameter(p.getName(), p.getIn(), Boolean.TRUE.equals(p.getRequired()) || "path".equals(p.getIn()), f.type()));
            }
        }
        PayloadDefinition.Field body = null;
        if (op.getRequestBody() != null) {
            Schema<?> s = jsonSchema(op.getRequestBody().getContent());
            if (s != null) {
                body = field("body", s, Boolean.TRUE.equals(op.getRequestBody().getRequired()), label, warnings,
                        Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            }
        }
        String status = null;
        PayloadDefinition.Field response = null;
        if (op.getResponses() != null) {
            for (Map.Entry<String, ApiResponse> r : op.getResponses().entrySet()) {
                if (r.getKey().startsWith("2")) {
                    status = r.getKey();
                    Schema<?> s = jsonSchema(r.getValue().getContent());
                    if (s != null) {
                        response = field("response", s, false, label, warnings, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
                    }
                    break; // the first 2xx is the one mapped (spec: "first 2xx response")
                }
            }
        }
        String id = op.getOperationId() != null && !op.getOperationId().isBlank() ? op.getOperationId() : label;
        return new Contract.Operation(id, method, path, op.getSummary(), params, body, status, response);
    }

    /** The JSON schema of a body: application/json, else any +json, else the first media type. */
    private static Schema<?> jsonSchema(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        MediaType mt = content.get("application/json");
        if (mt == null) {
            mt = content.entrySet().stream().filter(e -> e.getKey().endsWith("+json") || e.getKey().contains("json"))
                    .map(Map.Entry::getValue).findFirst().orElse(content.values().iterator().next());
        }
        return mt.getSchema();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PayloadDefinition.Field field(String name, Schema<?> s, boolean required, String label, List<String> warnings,
            Set<Schema<?>> path, int depth) {
        if (s == null) {
            return new PayloadDefinition.Field(null, name, "text", required, null, null, null, null);
        }
        if (s.get$ref() != null) {
            // resolveFully leaves a reference it can't inline, typically a schema that contains itself.
            warnings.add(label + ": " + name + " refers to itself (" + s.get$ref() + "); it is shown without its inner fields");
            return new PayloadDefinition.Field(null, name, "object", required, null, null, List.of(), null);
        }
        if (!path.add(s) || depth > MAX_DEPTH) {
            warnings.add(label + ": " + name + " refers to itself; it is shown without its inner fields");
            return new PayloadDefinition.Field(null, name, "object", required, null, null, List.of(), null);
        }
        try {
            // allOf: all parts together. oneOf/anyOf: the first option, with a warning.
            if (s.getAllOf() != null && !s.getAllOf().isEmpty()) {
                Map<String, Schema> props = new LinkedHashMap<>();
                List<String> req = new ArrayList<>();
                for (Schema part : s.getAllOf()) {
                    if (part.getProperties() != null) {
                        props.putAll(part.getProperties());
                    }
                    if (part.getRequired() != null) {
                        req.addAll(part.getRequired());
                    }
                }
                return objectField(name, props, req, required, label, warnings, path, depth);
            }
            List<Schema> options = s.getOneOf() != null && !s.getOneOf().isEmpty() ? s.getOneOf() : s.getAnyOf();
            if (options != null && !options.isEmpty()) {
                warnings.add(label + ": " + name + " can have " + options.size() + " different shapes; the first one is shown");
                return field(name, options.get(0), required, label, warnings, path, depth + 1);
            }
            String type = type(s);
            if ("array".equals(type)) {
                PayloadDefinition.Field items = field(null, s.getItems(), false, label, warnings, path, depth + 1);
                return new PayloadDefinition.Field(null, name, "list", required, null, null, null, items);
            }
            if ("object".equals(type) || s.getProperties() != null) {
                return objectField(name, s.getProperties() == null ? Map.of() : (Map) s.getProperties(),
                        s.getRequired() == null ? List.of() : s.getRequired(), required, label, warnings, path, depth);
            }
            return new PayloadDefinition.Field(null, name, simpleType(type, s.getFormat()), required, s.getFormat(), null, null, null);
        } finally {
            path.remove(s);
        }
    }

    @SuppressWarnings("rawtypes")
    private PayloadDefinition.Field objectField(String name, Map<String, Schema> props, List<String> req, boolean required,
            String label, List<String> warnings, Set<Schema<?>> path, int depth) {
        List<PayloadDefinition.Field> fields = new ArrayList<>();
        for (Map.Entry<String, Schema> p : props.entrySet()) {
            fields.add(field(p.getKey(), p.getValue(), req.contains(p.getKey()), label, warnings, path, depth + 1));
        }
        return new PayloadDefinition.Field(null, name, "object", required, null, null, fields, null);
    }

    private static String type(Schema<?> s) {
        if (s.getType() != null) {
            return s.getType();
        }
        if (s.getTypes() != null) {
            return s.getTypes().stream().filter(t -> !"null".equals(t)).findFirst().orElse(null);
        }
        return null;
    }

    /** {@code format: binary} is a file (D-29); {@code format: byte} is base64 text on the wire and stays text. */
    private static String simpleType(String type, String format) {
        if (type == null) {
            return "text";
        }
        return switch (type) {
            case "integer" -> "integer";
            case "number" -> "number";
            case "boolean" -> "boolean";
            case "string" -> switch (format == null ? "" : format) {
                case "date" -> "date";
                case "date-time" -> "datetime";
                case "binary" -> "blob";
                default -> "text";
            };
            default -> "text";
        };
    }
}
