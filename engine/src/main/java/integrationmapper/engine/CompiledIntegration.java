package integrationmapper.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.datasonnet.Mapper;
import com.datasonnet.MapperBuilder;
import com.datasonnet.document.DefaultDocument;
import com.datasonnet.document.MediaTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A published version, ready to serve. It keeps its own copies of the Input and Output definitions (D-9), its
 * outbound secret if the integration has auth, and DataSonnet scripts parsed once here and reused for every call
 * (performance principle 2).
 */
public final class CompiledIntegration {

    private final ServiceDefinition definition;
    private final PayloadDefinition input;
    private final PayloadDefinition output;
    private final String secret;
    /** Where the external system is in this environment, resolved from the connection when the version goes live (D-26). */
    private final String baseUrl;
    /** The connection's headers in this environment, resolved with the base URL (D-27). */
    private final List<Connections.Header> headers;
    private final Mapper request;
    private final Mapper response;
    /** Where the external answer has lists, for reading XML answers (D-24). */
    private final List<String> responseLists;
    /** Whether the response links read the whole answer as a file ({@code $body}, D-29). */
    private final boolean readsWholeAnswer;

    private CompiledIntegration(ServiceDefinition definition, PayloadDefinition input, PayloadDefinition output,
            String secret, String baseUrl, List<Connections.Header> headers, Mapper request, Mapper response) {
        this.definition = definition;
        this.input = input;
        this.output = output;
        this.secret = secret;
        this.baseUrl = baseUrl;
        this.headers = headers;
        this.request = request;
        this.response = response;
        this.responseLists = XmlFormat.listPaths(definition.response().links().stream().map(ServiceDefinition.Link::from).toList());
        this.readsWholeAnswer = definition.response().links().stream()
                .anyMatch(l -> l.from().equals(LinkRules.WHOLE_ANSWER) || l.from().startsWith(LinkRules.WHOLE_ANSWER + "."));
    }

    /** What callers get back: the body, its Content-Type and, for a file, its Content-Disposition (else null). */
    record Answer(byte[] body, String contentType, String contentDisposition) {
    }

    /** A file answer the integration can't pass on: too large, or missing where output binary needs one. Gives 502. */
    static final class RemoteFileException extends RuntimeException {
        RemoteFileException(String message) {
            super(message);
        }
    }

    /**
     * An answer is a file when the response links read {@code $body} and its Content-Type is neither JSON nor XML
     * (D-29); every other answer is mapped as JSON or XML, as before files existed.
     */
    boolean isFileAnswer(String contentType) {
        if (!readsWholeAnswer || contentType == null) {
            return false;
        }
        String t = contentType.toLowerCase();
        return !(t.startsWith("application/json") || t.contains("+json") || t.contains("xml"));
    }

    /** Maps the external answer as it came over the wire, bytes and headers, into the Output JSON. */
    public String mapAnswer(byte[] bytes, String contentType, String contentDisposition, long maxBlobBytes) {
        byte[] body = bytes == null ? new byte[0] : bytes;
        if (!isFileAnswer(contentType)) {
            return mapResponse(new String(body, charset(contentType)), contentType);
        }
        if (body.length > maxBlobBytes) {
            throw new RemoteFileException("The answer is a file of " + body.length + " bytes, more than the limit of " + maxBlobBytes + " bytes");
        }
        ObjectNode file = Blobs.fromRaw(body, contentType, contentDisposition).put("size", body.length);
        return completeFiles(mapResponse(Json.MAPPER.createObjectNode().set(LinkRules.WHOLE_ANSWER, file).toString()));
    }

    /** The mapped Output as callers get it: JSON, XML, or with output binary its one file as raw bytes (D-29). */
    Answer answer(String outputJson) throws JsonProcessingException {
        if (!definition.wire().outputBinary()) {
            return new Answer(outputBody(outputJson).getBytes(java.nio.charset.StandardCharsets.UTF_8), outputContentType(), null);
        }
        JsonNode file = Json.MAPPER.readTree(outputJson).path(output.fields().get(0).name());
        if (!file.isObject() || !file.path("data").isTextual()) {
            throw new RemoteFileException("The answer had no file to return");
        }
        return new Answer(Blobs.bytes(file), file.path("contentType").asText(Blobs.DEFAULT_TYPE),
                file.hasNonNull("fileName") ? Blobs.contentDisposition(file.get("fileName").asText()) : null);
    }

    /** The charset of a Content-Type, UTF-8 when it names none or an unknown one. */
    static java.nio.charset.Charset charset(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase().startsWith("charset=")) {
                    try {
                        return java.nio.charset.Charset.forName(p.substring(8).replace("\"", "").trim());
                    } catch (RuntimeException e) {
                        break;
                    }
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    public static CompiledIntegration compile(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output) {
        return compile(def, input, output, null);
    }

    /** Generates and parses both scripts; throws if either can't be built, so a broken version is never activated (R9). */
    public static CompiledIntegration compile(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output,
            String secret) {
        return compile(def, input, output, secret, def.target().baseUrl());
    }

    public static CompiledIntegration compile(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output,
            String secret, String baseUrl) {
        return compile(def, input, output, secret, baseUrl, List.of());
    }

    public static CompiledIntegration compile(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output,
            String secret, String baseUrl, List<Connections.Header> headers) {
        try {
            def.wire().check(def.id());
            checkFiles(def, input, output);
            // Both scripts are generated before failing, so the problems of request and response come at once (R8).
            String req = null;
            String res = null;
            List<String> problems = new ArrayList<>();
            try {
                req = ScriptGenerator.requestScript(def.request().links(), input, def.wire().request());
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
            try {
                res = ScriptGenerator.responseScript(def.response().links(), output);
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("; ", problems));
            }
            return new CompiledIntegration(def, input, output, secret, baseUrl, headers, build(req), build(res));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Integration " + def.id() + " " + def.version() + " does not compile: " + e.getMessage(), e);
        }
    }

    /** How files and formats fit together (D-29); the single links are checked by {@link LinkRules}. */
    private static void checkFiles(ServiceDefinition def, PayloadDefinition input, PayloadDefinition output) {
        ServiceDefinition.Formats f = def.wire();
        if ((f.requestBinary() || f.requestMultipart()) && !List.of("POST", "PUT", "PATCH").contains(def.target().method())) {
            throw new IllegalArgumentException("Integration " + def.id() + ": request format " + f.request()
                    + " needs POST, PUT or PATCH, which send a body");
        }
        if ((f.requestXml() && hasFile(input)) || (f.outputXml() && hasFile(output))) {
            throw new IllegalArgumentException("Integration " + def.id() + ": files (blobs) can't go into XML yet");
        }
        if (f.outputBinary()) {
            List<PayloadDefinition.Field> fields = output.fields() == null ? List.of() : output.fields();
            boolean oneFile = fields.size() == 1 && "blob".equals(fields.get(0).type());
            boolean linked = oneFile && def.response().links().stream()
                    .anyMatch(l -> l.to().equals(fields.get(0).id()) || l.to().startsWith(fields.get(0).id() + "."));
            if (!linked) {
                throw new IllegalArgumentException("Integration " + def.id()
                        + ": with output binary, the Output must be one linked file (blob); " + output.id() + " is not");
            }
        }
    }

    private static boolean hasFile(PayloadDefinition def) {
        return def.fields() != null && def.fields().stream().anyMatch(fd -> "blob".equals(fd.type()));
    }

    private static Mapper build(String script) {
        return new MapperBuilder(script).withDefaultOutput(MediaTypes.APPLICATION_JSON).build();
    }

    public ServiceDefinition definition() {
        return definition;
    }

    public PayloadDefinition input() {
        return input;
    }

    public PayloadDefinition output() {
        return output;
    }

    String secret() {
        return secret;
    }

    String baseUrl() {
        return baseUrl;
    }

    List<Connections.Header> headers() {
        return headers;
    }

    public String mapRequest(String inputJson) {
        return run(request, inputJson);
    }

    public String mapResponse(String externalJson) {
        return run(response, externalJson);
    }

    /**
     * Maps the external answer, reading it as XML when its Content-Type (or, without one, its text) says so. Files in
     * the Output get their size; broken base64 from the external system fails the mapping (D-29).
     */
    public String mapResponse(String body, String contentType) {
        String text = body == null || body.isBlank() ? "{}" : body;
        if (!XmlFormat.isXml(contentType, body) || body == null || body.isBlank()) {
            return completeFiles(mapResponse(text));
        }
        String mapped = run(response, XmlFormat.read(text, responseLists).toString());
        try {
            return XmlFormat.typed(Json.MAPPER.readTree(mapped), output.fields()).toString();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Files in the mapped Output get their size; broken base64 fails the mapping. */
    private String completeFiles(String mapped) {
        if (!hasFile(output)) {
            return mapped;
        }
        try {
            ObjectNode out = (ObjectNode) Json.MAPPER.readTree(mapped);
            Blobs.complete(out, output, Long.MAX_VALUE);
            return out.toString();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request body as the external system takes it. */
    String requestBody(JsonNode body) throws JsonProcessingException {
        ServiceDefinition.Formats f = definition.wire();
        return f.requestXml() ? XmlFormat.write(body, f.requestRoot()) : Json.MAPPER.writeValueAsString(body);
    }

    String requestContentType() {
        return definition.wire().requestXml() ? "application/xml" : "application/json";
    }

    /** What the external system may answer with: the request's own format, anything after a raw file (D-29). */
    String accept() {
        return definition.wire().requestBinary() ? "*/*" : requestContentType();
    }

    /** The mapped Output as callers get it. */
    String outputBody(String outputJson) throws JsonProcessingException {
        ServiceDefinition.Formats f = definition.wire();
        return f.outputXml() ? XmlFormat.write(Json.MAPPER.readTree(outputJson), f.outputRoot()) : outputJson;
    }

    String outputContentType() {
        return definition.wire().outputXml() ? "application/xml" : "application/json";
    }

    private static String run(Mapper mapper, String json) {
        return mapper.transform(new DefaultDocument<>(json, MediaTypes.APPLICATION_JSON), Map.of(), MediaTypes.APPLICATION_JSON)
                .getContent();
    }
}
