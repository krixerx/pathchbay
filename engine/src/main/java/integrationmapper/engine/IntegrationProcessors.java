package integrationmapper.engine;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.springframework.stereotype.Component;

/** The three steps every integration route runs: map the inbound call, map the external response, map failures. */
@Component
public class IntegrationProcessors {

    static final String SLUG = "integrationSlug";
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)}");

    private final IntegrationRegistry registry;

    public IntegrationProcessors(IntegrationRegistry registry) {
        this.registry = registry;
    }

    void mapRequest(Exchange exchange) throws Exception {
        Message msg = exchange.getMessage();
        CompiledIntegration ci = registry.get(exchange.getProperty(SLUG, String.class));
        ServiceDefinition.Target target = ci.definition().target();

        String input = BODY_METHODS.contains(target.method())
                ? blankToEmptyObject(msg.getBody(String.class))
                : queryToJson(msg.getHeader(Exchange.HTTP_QUERY, String.class));
        if (!isJsonObject(input)) {
            throw new BadInputException("Request body must be a JSON object");
        }

        JsonNode mapped;
        try {
            mapped = Json.MAPPER.readTree(ci.mapRequest(input));
        } catch (Exception e) {
            throw new MappingException("request mapping failed: " + e.getMessage(), e);
        }

        // Nothing from the caller reaches the external system except what the mapping produces (OV-8).
        msg.removeHeaders("*");
        msg.setHeader(Exchange.HTTP_METHOD, target.method());
        msg.setHeader(Exchange.HTTP_URI, stripTrailingSlash(target.baseUrl()) + fillPath(target.path(), mapped.path("path")));
        String query = toQuery(mapped.path("query"));
        if (!query.isEmpty()) {
            msg.setHeader(Exchange.HTTP_QUERY, query);
        }
        msg.setHeader("Accept", "application/json");
        mapped.path("headers").properties().forEach(h -> {
            if (!h.getValue().isNull()) {
                msg.setHeader(h.getKey(), h.getValue().asText());
            }
        });
        if (BODY_METHODS.contains(target.method())) {
            msg.setHeader(Exchange.CONTENT_TYPE, "application/json");
            msg.setBody(Json.MAPPER.writeValueAsString(mapped.path("body")));
        } else {
            msg.setBody(null);
        }
    }

    void mapResponse(Exchange exchange) throws Exception {
        Message msg = exchange.getMessage();
        CompiledIntegration ci = registry.get(exchange.getProperty(SLUG, String.class));
        int status = msg.getHeader(Exchange.HTTP_RESPONSE_CODE, 200, Integer.class);
        String body = msg.getBody(String.class);
        msg.removeHeaders("*");
        msg.setHeader(Exchange.CONTENT_TYPE, "application/json");

        if (status >= 400) {
            // Runtime rule: external errors pass through with their status and body.
            msg.setHeader(Exchange.HTTP_RESPONSE_CODE, status);
            msg.setHeader("X-Integration-Error", "remote");
            msg.setBody(body);
            return;
        }
        try {
            msg.setBody(ci.mapResponse(blankToEmptyObject(body)));
        } catch (Exception e) {
            throw new MappingException("response mapping failed: " + e.getMessage(), e);
        }
        msg.setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
    }

    void mapError(Exchange exchange) {
        Exception e = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String slug = exchange.getProperty(SLUG, String.class);
        int status;
        String error;
        if (hasCause(e, BadInputException.class)) {
            status = 400;
            error = "bad-request";
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
        msg.setBody(Json.error(error, slug, e == null ? null : rootMessage(e)));
    }

    // ---- helpers

    private static String fillPath(String template, JsonNode values) {
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

    private static String toQuery(JsonNode query) {
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
    static String queryToJson(String rawQuery) {
        ObjectNode obj = Json.MAPPER.createObjectNode();
        if (rawQuery == null || rawQuery.isBlank()) {
            return obj.toString();
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
        return obj.toString();
    }

    private static boolean isJsonObject(String s) {
        try {
            return Json.MAPPER.readTree(s).isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private static String blankToEmptyObject(String s) {
        return s == null || s.isBlank() ? "{}" : s;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && !(c instanceof BadInputException) && !(c instanceof MappingException)) {
            c = c.getCause();
        }
        return c.getMessage();
    }

    static final class BadInputException extends RuntimeException {
        BadInputException(String message) {
            super(message);
        }
    }

    static final class MappingException extends RuntimeException {
        MappingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
