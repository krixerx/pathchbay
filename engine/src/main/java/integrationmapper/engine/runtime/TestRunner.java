package integrationmapper.engine.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import integrationmapper.engine.config.ConfigStore;
import integrationmapper.engine.config.Connections;
import integrationmapper.engine.config.PayloadLibrary;
import integrationmapper.engine.config.SecretResolver;
import integrationmapper.engine.format.Blobs;
import integrationmapper.engine.format.Json;
import integrationmapper.engine.format.Multipart;
import integrationmapper.engine.format.SoapEnvelope;
import integrationmapper.engine.mapping.LinkRules;
import integrationmapper.engine.mapping.MockGenerator;
import integrationmapper.engine.model.Contract;
import integrationmapper.engine.model.Operation;
import integrationmapper.engine.model.PayloadDefinition;
import integrationmapper.engine.model.RestOperation;
import integrationmapper.engine.model.ServiceDefinition;
import integrationmapper.engine.model.SoapOperation;

/**
 * The Test drawer (D-2, OV-7): runs a sample input through the draft with the same validation, mapping scripts and
 * request building as a live call, without publishing. The external answer is a mock built from the contract by
 * default; the real system is called only when asked. Auth values are never returned, only shown as ***.
 */
@Component
public class TestRunner {

    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final ConfigStore store;
    private final PayloadLibrary library;
    private final SecretResolver secrets;
    private final Connections connections;
    private final HttpClient http;
    private final long maxBlobBytes;

    public TestRunner(ConfigStore store, PayloadLibrary library, SecretResolver secrets, Connections connections,
            @Value("${mapper.outbound-timeout-ms:10000}") long timeoutMs, @Value("${mapper.max-blob-bytes:52428800}") long maxBlobBytes) {
        this.store = store;
        this.library = library;
        this.secrets = secrets;
        this.connections = connections;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        this.maxBlobBytes = maxBlobBytes;
    }

    /** What the drawer shows, step by step; a step that failed stops the rest and fills {@code problem}. */
    public record Result(String stage, List<String> problems, Map<String, Object> request, Map<String, Object> response,
            JsonNode output, boolean mock) {
    }

    public Result run(String slug, JsonNode input, boolean real) throws IOException {
        ServiceDefinition def = Json.MAPPER.readValue(store.service(slug).toFile(), ServiceDefinition.class);
        if (def.input() == null || def.output() == null || def.target() == null || def.target().method() == null) {
            return failed("setup", List.of("Choose the external system, the operation, the Input and the Output first"));
        }
        PayloadDefinition in = library.get(def.input());
        PayloadDefinition out = library.get(def.output());
        CompiledIntegration ci;
        try {
            ci = CompiledIntegration.compile(def.withVersion("test"), in, out, null);
        } catch (IllegalArgumentException e) {
            return failed("links", List.of(e.getMessage()));
        }

        // 1. Input, as a caller would send it.
        JsonNode callerInput = input;
        if (!BODY_METHODS.contains(def.target().method()) && input.isObject()) {
            callerInput = InputValidator.coerceQuery((ObjectNode) input, in);
        }
        List<String> problems = InputValidator.validate(callerInput, in);
        if (!problems.isEmpty()) {
            return failed("input", problems);
        }
        if (callerInput instanceof ObjectNode obj) {
            try {
                Blobs.complete(obj, in, maxBlobBytes);
            } catch (Blobs.TooLargeException e) {
                return failed("input", List.of(e.getMessage()));
            }
        }

        // 2. The request the engine would send. Without a base URL or a header value yet the mock still runs; a real
        // call can't.
        String baseUrl;
        String noBaseUrl = null;
        try {
            baseUrl = connections.resolve(def);
        } catch (IllegalArgumentException e) {
            baseUrl = "";
            noBaseUrl = e.getMessage();
        }
        List<Connections.Header> connectionHeaders = List.of();
        try {
            connectionHeaders = connections.headers(def);
        } catch (IllegalArgumentException e) {
            noBaseUrl = noBaseUrl == null ? e.getMessage() : noBaseUrl;
        }
        JsonNode mapped;
        String url;
        try {
            mapped = Json.MAPPER.readTree(ci.mapRequest(callerInput.toString()));
            url = IntegrationProcessors.stripTrailingSlash(baseUrl)
                    + IntegrationProcessors.fillPath(def.target().path(), mapped.path("path"));
        } catch (RuntimeException e) {
            return failed("request", List.of(e.getMessage()));
        }
        String query = IntegrationProcessors.toQuery(mapped.path("query"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", ci.accept());
        mapped.path("headers").properties().forEach(h -> {
            if (!h.getValue().isNull()) {
                headers.put(h.getKey(), h.getValue().asText());
            }
        });
        // As in the route: the connection's headers win over mapped ones of the same name (D-27).
        for (Connections.Header h : connectionHeaders) {
            headers.keySet().removeIf(k -> k.equalsIgnoreCase(h.name()));
            headers.put(h.name(), h.value());
        }
        // As in the route: SOAP 1.1 names the operation in SOAPAction (D-30).
        if (ci.soap() != null && SoapEnvelope.soapAction(ci.soap()) != null) {
            headers.put("SOAPAction", SoapEnvelope.soapAction(ci.soap()));
        }
        String authHeader = null;
        String authQuery = null;
        if (def.auth() != null) {
            authHeader = "bearer".equals(def.auth().type()) || "basic".equals(def.auth().type()) ? "Authorization"
                    : "header".equals(def.auth().in()) ? def.auth().name() : null;
            authQuery = "query".equals(def.auth().in()) ? def.auth().name() : null;
        }
        boolean hasBody = BODY_METHODS.contains(def.target().method());
        // A file or a multipart form goes out as bytes (D-29); JSON and XML as text.
        boolean bytesBody = hasBody && (def.wire().requestBinary() || def.wire().requestMultipart());
        String body = hasBody && !bytesBody ? ci.requestBody(mapped.path("body")) : null;
        byte[] bodyBytes = null;
        if (hasBody && def.wire().requestBinary()) {
            // As in the route: one file as the raw body, with its type and name (D-29).
            JsonNode file = mapped.path("body");
            bodyBytes = file.isObject() ? Blobs.bytes(file) : new byte[0];
            headers.put("Content-Type", file.path("contentType").asText(Blobs.DEFAULT_TYPE));
            if (file.hasNonNull("fileName")) {
                headers.put("Content-Disposition", Blobs.contentDisposition(file.get("fileName").asText()));
            }
        } else if (hasBody && def.wire().requestMultipart()) {
            // As in the route: one part per body field (D-29).
            Multipart.Body form = Multipart.write(mapped.path("body"));
            bodyBytes = form.bytes();
            headers.put("Content-Type", form.contentType());
        }

        Map<String, Object> shownHeaders = new LinkedHashMap<>(headers);
        // Shown only: a real call sets the Content-Type once below, and for SOAP it carries the 1.2 action (D-30).
        if (hasBody && ci.soap() != null) {
            shownHeaders.put("Content-Type", ci.requestContentType());
        }
        IntegrationProcessors.maskSecrets(shownHeaders, connectionHeaders);
        if (authHeader != null) {
            String auth = authHeader;
            shownHeaders.keySet().removeIf(k -> k.equalsIgnoreCase(auth));
            shownHeaders.put(authHeader, "***");
        }
        String shownUrl = url + (query.isEmpty() && authQuery == null ? ""
                : "?" + query + (authQuery == null ? "" : (query.isEmpty() ? "" : "&") + IntegrationProcessors.encode(authQuery) + "=***"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", def.target().method());
        request.put("url", shownUrl);
        request.put("headers", shownHeaders);
        request.put("body", !hasBody ? null : def.wire().requestXml() || def.wire().requestSoap() ? Json.MAPPER.getNodeFactory().textNode(body)
                : Blobs.forLog(mapped.path("body")));

        // 3. The answer: mock from the contract, or the real system.
        int status;
        byte[] responseBytes;
        String contentType = "application/json";
        String contentDisposition = null;
        if (!real) {
            Operation op = operation(def);
            String declared = switch (op) {
                case null -> null;
                case RestOperation rest -> rest.responseStatus();
                case SoapOperation soap -> soap.responseStatus();
            };
            status = declared == null ? 200 : Integer.parseInt(declared);
            JsonNode mock = MockGenerator.example(op == null ? null : op.response());
            if (op != null && op.response() != null && "blob".equals(op.response().type())) {
                // A file answer is mocked as the mapping sees it: the whole answer under $body (D-29).
                mock = Json.MAPPER.createObjectNode().set(LinkRules.WHOLE_ANSWER, mock);
            }
            if (ci.soap() != null) {
                // A SOAP answer is mocked as an envelope around the response element (D-30).
                contentType = SoapEnvelope.accept(ci.soap()) + "; charset=utf-8";
                responseBytes = SoapEnvelope.mockAnswer(ci.soap(), op == null || op.response() == null ? null : mock)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                responseBytes = mock.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        } else {
            if (noBaseUrl != null) {
                return new Result("request", List.of(noBaseUrl), request, null, null, false);
            }
            String secret = null;
            if (def.auth() != null) {
                try {
                    secret = secrets.resolve(slug);
                } catch (IllegalArgumentException e) {
                    return new Result("request", List.of(e.getMessage()), request, null, null, false);
                }
            }
            String realQuery = query;
            if (authQuery != null) {
                String p = IntegrationProcessors.encode(authQuery) + "=" + IntegrationProcessors.encode(secret);
                realQuery = realQuery.isEmpty() ? p : realQuery + "&" + p;
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url + (realQuery.isEmpty() ? "" : "?" + realQuery)))
                    .timeout(Duration.ofSeconds(10))
                    .method(def.target().method(), bytesBody ? HttpRequest.BodyPublishers.ofByteArray(bodyBytes)
                            : hasBody ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody());
            String auth = authHeader;
            headers.forEach((k, v) -> {
                if (auth == null || !k.equalsIgnoreCase(auth)) {
                    rb.header(k, v);
                }
            });
            if (hasBody && !bytesBody) {
                rb.header("Content-Type", ci.requestContentType());
            }
            if (authHeader != null) {
                String value = "basic".equals(def.auth().type())
                        ? "Basic " + java.util.Base64.getEncoder().encodeToString(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : "Authorization".equals(authHeader) ? "Bearer " + secret : secret;
                rb.header(authHeader, value);
            }
            HttpRequest built = rb.build();
            try {
                HttpResponse<byte[]> r = http.send(built, HttpResponse.BodyHandlers.ofByteArray());
                status = r.statusCode();
                responseBytes = r.body();
                contentType = r.headers().firstValue("Content-Type").orElse(null);
                contentDisposition = r.headers().firstValue("Content-Disposition").orElse(null);
            } catch (IOException e) {
                return new Result("response", List.of(noAnswer(e, built.uri())), request, null, null, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
        String responseText = ci.isFileAnswer(contentType) ? null : new String(responseBytes, CompiledIntegration.charset(contentType));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("body", responseText == null ? Blobs.describe(responseBytes, contentType, contentDisposition)
                : Blobs.forLog(parseOrText(responseText)));

        // 4. Mapped back into the Output, as callers would get it.
        SoapEnvelope.Answer soapAnswer = ci.soap() == null || responseText == null ? null : SoapEnvelope.read(responseText);
        if (soapAnswer != null && soapAnswer.fault() != null) {
            SoapEnvelope.Fault f = soapAnswer.fault();
            return new Result("response", List.of("The external system answered " + status + " with a SOAP Fault: " + f.code() + " "
                    + f.reason() + "; callers get " + (status >= 400 ? status : 502) + " remote-fault"), request, response, null, !real);
        }
        if (status >= 400) {
            return new Result("response", List.of("The external system answered " + status + "; callers get this status and body"),
                    request, response, null, !real);
        }
        List<String> notes = !real && "{}".equals(responseText)
                ? List.of("The contract does not describe the response of this operation, so the mock answer is empty. Try a real call to see real data.")
                : List.of();
        try {
            if (ci.soap() != null && soapAnswer == null) {
                throw new IllegalArgumentException("the answer is not a SOAP envelope");
            }
            String mappedOutput = ci.soap() != null ? ci.mapSoapBody(soapAnswer.bodyXml())
                    : ci.mapAnswer(responseBytes, contentType, contentDisposition, maxBlobBytes);
            CompiledIntegration.Answer answer = ci.answer(mappedOutput); // as callers get it; with output binary the file is shown as JSON
            JsonNode output = def.wire().outputXml() ? Json.MAPPER.getNodeFactory().textNode(new String(answer.body(), java.nio.charset.StandardCharsets.UTF_8))
                    : Json.MAPPER.readTree(mappedOutput);
            return new Result("done", notes, request, response, Blobs.forLog(output), !real);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Result("output", List.of("Response mapping failed: " + e.getMessage()), request, response, null, !real);
        }
    }

    private Operation operation(ServiceDefinition def) throws IOException {
        JsonNode draft = Json.MAPPER.readTree(store.service(def.id()).toFile());
        String contractId = draft.path("contract").asText(null);
        String opId = draft.path("operationId").asText(null);
        if (contractId == null || opId == null) {
            return null;
        }
        Path p = store.root().resolve("contracts").resolve(contractId + ".json");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        Contract c = Json.MAPPER.readValue(p.toFile(), Contract.class);
        return c.operations().stream().filter(o -> o.id().equals(opId)).findFirst().orElse(null);
    }

    private static JsonNode parseOrText(String text) {
        try {
            return text == null || text.isBlank() ? null : Json.MAPPER.readTree(text);
        } catch (IOException e) {
            return Json.MAPPER.getNodeFactory().textNode(text);
        }
    }

    private static Result failed(String stage, List<String> problems) {
        return new Result(stage, problems, null, null, null, false);
    }

    /**
     * Why no answer came back, in plain words: the Java exception's own message is often missing ("null") or says
     * nothing a user can act on, so the cause and the address are named instead.
     */
    static String noAnswer(IOException e, URI uri) {
        String where = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        if (hasCause(e, java.net.http.HttpTimeoutException.class)) {
            return "The external system at " + where + " did not answer in time.";
        }
        if (hasCause(e, java.net.ConnectException.class)) {
            return "Nothing answered at " + where + ": the connection was refused. Check the base URL of the connection under Configuration and that the system is running.";
        }
        if (hasCause(e, java.net.UnknownHostException.class)) {
            return "The host " + uri.getHost() + " is not known. Check the base URL of the connection under Configuration.";
        }
        String message = e.getMessage();
        return "The external system at " + where + " did not answer: "
                + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
