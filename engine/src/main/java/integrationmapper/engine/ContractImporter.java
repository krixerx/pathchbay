package integrationmapper.engine;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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

    public ContractImporter(ConfigStore store) {
        this.store = store;
    }

    /** Fetches a contract by URL from the engine. An unreachable URL gets the D-5 message: upload the file instead. */
    public String fetch(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
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
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIParser().readContents(text, null, options);
        OpenAPI api = result.getOpenAPI();
        List<String> warnings = new ArrayList<>(result.getMessages() == null ? List.of() : result.getMessages());
        boolean declared = text.contains("openapi") || text.contains("swagger");
        if (api == null || api.getPaths() == null || !declared) {
            throw new IllegalArgumentException("Not an OpenAPI or Swagger file" + (warnings.isEmpty() ? "" : ": " + String.join("; ", warnings)));
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
        store.write(store.root().resolve("contracts").resolve(sourceFile), out -> out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Contract contract = new Contract(id, name, sourceFile, sourceUrl, OffsetDateTime.now(ZoneOffset.UTC).toString(), servers,
                operations, warnings);
        store.writeJson(store.root().resolve("contracts").resolve(id + ".json"), contract);
        return contract;
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
