package integrationmapper.engine;

import java.util.List;
import java.util.Map;

import com.datasonnet.Mapper;
import com.datasonnet.MapperBuilder;
import com.datasonnet.document.DefaultDocument;
import com.datasonnet.document.MediaTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

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
            String req = ScriptGenerator.requestScript(def.request().links(), input);
            String res = ScriptGenerator.responseScript(def.response().links(), output);
            return new CompiledIntegration(def, input, output, secret, baseUrl, headers, build(req), build(res));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Integration " + def.id() + " " + def.version() + " does not compile: " + e.getMessage(), e);
        }
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

    /** Maps the external answer, reading it as XML when its Content-Type (or, without one, its text) says so. */
    public String mapResponse(String body, String contentType) {
        String text = body == null || body.isBlank() ? "{}" : body;
        if (!XmlFormat.isXml(contentType, body) || body == null || body.isBlank()) {
            return mapResponse(text);
        }
        String mapped = run(response, XmlFormat.read(text, responseLists).toString());
        try {
            return XmlFormat.typed(Json.MAPPER.readTree(mapped), output.fields()).toString();
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
