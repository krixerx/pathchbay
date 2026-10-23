package integrationmapper.engine;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The steps every integration route runs: map the inbound call, map the external response, map failures, and
 * record the call (D-22).
 */
@Component
public class IntegrationProcessors {

    static final String SLUG = "integrationSlug";
    private static final String TRACE = "integrationTrace";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)}");

    private final IntegrationRegistry registry;
    private final CallLog calls;
    /** The largest file a caller may send (D-29), decoded; 50 MiB unless {@code mapper.max-blob-bytes} says otherwise. */
    private final long maxBlobBytes;

    public IntegrationProcessors(IntegrationRegistry registry, CallLog calls,
            @Value("${mapper.max-blob-bytes:52428800}") long maxBlobBytes) {
        this.registry = registry;
        this.calls = calls;
        this.maxBlobBytes = maxBlobBytes;
    }

    /** What one call did so far, filled step by step; {@code stage} is the step it is in (the Test drawer's names). */
    private static final class Trace {
        final long start = System.nanoTime();
        final Instant at = Instant.now();
        String version;
        String method = "?";
        String stage = "input";
        List<String> problems = List.of();
        JsonNode input;
        Map<String, Object> request;
        Map<String, Object> response;
        JsonNode output;
    }

    void mapRequest(Exchange exchange) throws Exception {
        Trace trace = new Trace();
        exchange.setProperty(TRACE, trace);
        Message msg = exchange.getMessage();
        CompiledIntegration ci = registry.get(exchange.getProperty(SLUG, String.class));
        ServiceDefinition.Target target = ci.definition().target();
        trace.version = ci.definition().version();
        trace.method = target.method();

        JsonNode input;
        String contentType = msg.getHeader(Exchange.CONTENT_TYPE, String.class);
        PayloadDefinition.Field file = Blobs.onlyFile(ci.input());
        if (BODY_METHODS.contains(target.method()) && Multipart.isMultipart(contentType)) {
            // Multipart (D-29): one part per Input field, files as file parts.
            try {
                input = Multipart.toInput(Multipart.read(msg.getBody(byte[].class), contentType), ci.input());
            } catch (IllegalArgumentException e) {
                throw new BadInputException(e.getMessage());
            }
        } else if (BODY_METHODS.contains(target.method()) && file != null && contentType != null
                && !contentType.toLowerCase().startsWith("application/json")) {
            // A raw file (D-29): the body is the Input's file, its other fields come as query parameters.
            ObjectNode fromQuery = InputValidator.coerceQuery(queryToJson(msg.getHeader(Exchange.HTTP_QUERY, String.class)), ci.input());
            fromQuery.set(file.name(), Blobs.fromRaw(msg.getBody(byte[].class), contentType, msg.getHeader("Content-Disposition", String.class)));
            input = fromQuery;
        } else if (BODY_METHODS.contains(target.method())) {
            input = parseObject(blankToEmptyObject(msg.getBody(String.class)));
        } else {
            input = InputValidator.coerceQuery(queryToJson(msg.getHeader(Exchange.HTTP_QUERY, String.class)), ci.input());
        }
        trace.input = Blobs.forLog(input);
        List<String> problems = InputValidator.validate(input, ci.input());
        if (!problems.isEmpty()) {
            throw new BadInputException("Input does not match " + ci.input().id(), problems);
        }
        if (input instanceof ObjectNode obj) {
            Blobs.complete(obj, ci.input(), maxBlobBytes);
            trace.input = Blobs.forLog(input);
        }
        trace.stage = "request";

        JsonNode mapped;
        try {
            mapped = Json.MAPPER.readTree(ci.mapRequest(input.toString()));
        } catch (Exception e) {
            throw new MappingException("request mapping failed: " + e.getMessage(), e);
        }

        // Nothing from the caller reaches the external system except what the mapping produces (OV-8).
        msg.removeHeaders("*");
        msg.setHeader(Exchange.HTTP_METHOD, target.method());
        msg.setHeader(Exchange.HTTP_URI, stripTrailingSlash(ci.baseUrl()) + fillPath(target.path(), mapped.path("path")));
        String query = toQuery(mapped.path("query"));
        msg.setHeader("Accept", ci.accept());
        mapped.path("headers").properties().forEach(h -> {
            if (!h.getValue().isNull()) {
                msg.setHeader(h.getKey(), h.getValue().asText());
            }
        });
        // The connection's headers win over mapped ones of the same name; the auth header wins over both (D-27).
        ci.headers().forEach(h -> msg.setHeader(h.name(), h.value()));
        // What the call log shows: the same request, with auth and secret header values as *** (D-22).
        Map<String, Object> shownHeaders = new LinkedHashMap<>();
        msg.getHeaders().forEach((k, v) -> {
            if (!k.startsWith("Camel")) {
                shownHeaders.put(k, v);
            }
        });
        maskSecrets(shownHeaders, ci.headers());
        String shownQuery = query;
        ServiceDefinition.Auth auth = ci.definition().auth();
        if (auth != null) {
            if ("bearer".equals(auth.type())) {
                msg.setHeader("Authorization", "Bearer " + ci.secret());
                shownHeaders.put("Authorization", "***");
            } else if ("header".equals(auth.in())) {
                msg.setHeader(auth.name(), ci.secret());
                shownHeaders.put(auth.name(), "***");
            } else {
                String param = encode(auth.name()) + "=" + encode(ci.secret());
                query = query.isEmpty() ? param : query + "&" + param;
                String shownParam = encode(auth.name()) + "=***";
                shownQuery = shownQuery.isEmpty() ? shownParam : shownQuery + "&" + shownParam;
            }
        }
        if (!query.isEmpty()) {
            msg.setHeader(Exchange.HTTP_QUERY, query);
        }
        boolean hasBody = BODY_METHODS.contains(target.method());
        if (hasBody && ci.definition().wire().requestBinary()) {
            // One file as the raw body, with its type and name (D-29).
            JsonNode sentFile = mapped.path("body");
            byte[] bytes = sentFile.isObject() ? Blobs.bytes(sentFile) : new byte[0];
            msg.setHeader(Exchange.CONTENT_TYPE, sentFile.path("contentType").asText(Blobs.DEFAULT_TYPE));
            shownHeaders.put(Exchange.CONTENT_TYPE, msg.getHeader(Exchange.CONTENT_TYPE));
            if (sentFile.hasNonNull("fileName")) {
                msg.setHeader("Content-Disposition", Blobs.contentDisposition(sentFile.get("fileName").asText()));
                shownHeaders.put("Content-Disposition", msg.getHeader("Content-Disposition"));
            }
            msg.setBody(bytes);
        } else if (hasBody && ci.definition().wire().requestMultipart()) {
            // One part per body field, files as file parts (D-29).
            Multipart.Body form = Multipart.write(mapped.path("body"));
            msg.setHeader(Exchange.CONTENT_TYPE, form.contentType());
            shownHeaders.put(Exchange.CONTENT_TYPE, form.contentType());
            msg.setBody(form.bytes());
        } else if (hasBody) {
            msg.setHeader(Exchange.CONTENT_TYPE, ci.requestContentType());
            msg.setBody(ci.requestBody(mapped.path("body")));
        } else {
            msg.setBody(null);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", target.method());
        request.put("url", msg.getHeader(Exchange.HTTP_URI, String.class) + (shownQuery.isEmpty() ? "" : "?" + shownQuery));
        request.put("headers", shownHeaders);
        request.put("body", !hasBody ? null
                : ci.definition().wire().requestXml() ? Json.MAPPER.getNodeFactory().textNode(msg.getBody(String.class))
                : Blobs.forLog(mapped.path("body")));
        trace.request = request;
        trace.stage = "response";
    }

    void mapResponse(Exchange exchange) throws Exception {
        Message msg = exchange.getMessage();
        CompiledIntegration ci = registry.get(exchange.getProperty(SLUG, String.class));
        int status = msg.getHeader(Exchange.HTTP_RESPONSE_CODE, 200, Integer.class);
        // Read as bytes: the answer may be a file (D-29); text is decoded with its own charset.
        byte[] bytes = msg.getBody(byte[].class);
        bytes = bytes == null ? new byte[0] : bytes;
        String contentType = msg.getHeader(Exchange.CONTENT_TYPE, String.class);
        String contentDisposition = msg.getHeader("Content-Disposition", String.class);
        msg.removeHeaders("*");
        Trace trace = exchange.getProperty(TRACE, Trace.class);
        if (calls.enabled()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", status);
            response.put("body", ci.isFileAnswer(contentType) ? Blobs.describe(bytes, contentType, contentDisposition)
                    : Blobs.forLog(parseOrText(new String(bytes, CompiledIntegration.charset(contentType)))));
            trace.response = response;
        }

        if (status >= 400) {
            trace.problems = List.of("The external system answered " + status + "; the caller got this status and body");
            // Runtime rule: external errors pass through with their status and body, byte for byte.
            msg.setHeader(Exchange.HTTP_RESPONSE_CODE, status);
            msg.setHeader("X-Integration-Error", "remote");
            msg.setHeader(Exchange.CONTENT_TYPE, contentType == null ? "application/json" : contentType);
            msg.setBody(bytes);
            return;
        }
        trace.stage = "output";
        String output;
        CompiledIntegration.Answer answer;
        try {
            output = ci.mapAnswer(bytes, contentType, contentDisposition, maxBlobBytes);
            answer = ci.answer(output);
        } catch (CompiledIntegration.RemoteFileException e) {
            throw e;
        } catch (Exception e) {
            throw new MappingException("response mapping failed: " + e.getMessage(), e);
        }
        msg.setHeader(Exchange.CONTENT_TYPE, answer.contentType());
        if (answer.contentDisposition() != null) {
            msg.setHeader("Content-Disposition", answer.contentDisposition());
        }
        msg.setBody(answer.body());
        msg.setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        if (calls.enabled()) {
            trace.output = Blobs.forLog(parseOrText(output));
        }
        trace.stage = "done";
    }

    void mapError(Exchange exchange) {
        Exception e = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String slug = exchange.getProperty(SLUG, String.class);
        int status;
        String error;
        if (hasCause(e, BadInputException.class)) {
            status = 400;
            error = "bad-request";
        } else if (hasCause(e, Blobs.TooLargeException.class)) {
            status = 413;
            error = "payload-too-large";
        } else if (hasCause(e, CompiledIntegration.RemoteFileException.class)) {
            status = 502;
            error = "remote-failure";
        } else if (hasCause(e, MappingException.class)) {
            status = 500;
            error = "mapping-error";
        } else if (hasCause(e, SocketTimeoutException.class)) {
            status = 504;
            error = "remote-timeout";
        } else if (hasCause(e, ConnectException.class)) {
            status = 502;
            error = "remote-unreachable";
        } else {
            status = 502;
            error = "remote-failure";
        }
        Message msg = exchange.getMessage();
        msg.removeHeaders("*");
        msg.setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        msg.setHeader(Exchange.CONTENT_TYPE, "application/json");
        List<String> problems = e == null ? List.of() : findCause(e, BadInputException.class).map(b -> b.problems).orElse(List.of());
        msg.setBody(Json.error(error, slug, e == null ? null : rootMessage(e), problems));
        Trace trace = exchange.getProperty(TRACE, Trace.class);
        if (trace != null) {
            List<String> shown = new ArrayList<>();
            if (e != null) {
                shown.add(rootMessage(e));
            }
            shown.addAll(problems);
            trace.problems = shown;
        }
    }

    /** Last step of every call: one log line, and the call kept for the console while the call log is on (D-22). */
    void record(Exchange exchange) {
        Trace trace = exchange.getProperty(TRACE, Trace.class);
        if (trace == null) {
            return;
        }
        int status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 200, Integer.class);
        long ms = (System.nanoTime() - trace.start) / 1_000_000;
        boolean keep = calls.enabled();
        calls.add(exchange.getProperty(SLUG, String.class), trace.method, new CallLog.Call(trace.at.toString(), trace.version, status, ms,
                trace.stage, trace.problems, keep ? trace.input : null, keep ? trace.request : null, trace.response, trace.output));
    }

    // ---- helpers

    /** Shows secret connection headers as ***, whatever the case of the name they were sent under. */
    static void maskSecrets(Map<String, ? super String> shown, List<Connections.Header> headers) {
        for (Connections.Header h : headers) {
            if (h.secret()) {
                shown.keySet().removeIf(k -> k.equalsIgnoreCase(h.name()));
                shown.put(h.name(), "***");
            }
        }
    }

    static String fillPath(String template, JsonNode values) {
        Matcher m = PATH_PARAM.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            JsonNode v = values.path(m.group(1));
            if (v.isMissingNode() || v.isNull()) {
                throw new BadInputException("Path parameter '" + m.group(1) + "' has no value");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(encode(v.asText())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String toQuery(JsonNode query) {
        StringJoiner sj = new StringJoiner("&");
        query.properties().forEach(p -> {
            if (p.getValue().isArray()) {
                p.getValue().forEach(v -> sj.add(encode(p.getKey()) + "=" + encode(v.asText())));
            } else if (!p.getValue().isNull()) {
                sj.add(encode(p.getKey()) + "=" + encode(p.getValue().asText()));
            }
        });
        return sj.toString();
    }

    /** GET/DELETE input: query parameters become a flat JSON object; repeated names become a list (R1, D16). */
    static ObjectNode queryToJson(String rawQuery) {
        ObjectNode obj = Json.MAPPER.createObjectNode();
        if (rawQuery == null || rawQuery.isBlank()) {
            return obj;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            JsonNode existing = obj.get(key);
            if (existing == null) {
                obj.put(key, value);
            } else if (existing.isArray()) {
                ((ArrayNode) existing).add(value);
            } else {
                ArrayNode arr = obj.putArray(key);
                arr.add(existing.asText());
                arr.add(value);
            }
        }
        return obj;
    }

    private static JsonNode parseOrText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Json.MAPPER.readTree(text);
        } catch (Exception e) {
            return Json.MAPPER.getNodeFactory().textNode(text);
        }
    }

    private static JsonNode parseObject(String s) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new BadInputException("Request body must be a JSON object");
        }
        if (!node.isObject()) {
            throw new BadInputException("Request body must be a JSON object");
        }
        return node;
    }

    private static String blankToEmptyObject(String s) {
        return s == null || s.isBlank() ? "{}" : s;
    }

    static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        return findCause(t, type).isPresent();
    }

    private static <T extends Throwable> Optional<T> findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return Optional.of(type.cast(c));
            }
        }
        return Optional.empty();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && !(c instanceof BadInputException) && !(c instanceof MappingException)) {
            c = c.getCause();
        }
        return c.getMessage();
    }

    static final class BadInputException extends RuntimeException {
        final List<String> problems;

        BadInputException(String message) {
            this(message, List.of());
        }

        BadInputException(String message, List<String> problems) {
            super(message);
            this.problems = problems;
        }
    }

    static final class MappingException extends RuntimeException {
        MappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
