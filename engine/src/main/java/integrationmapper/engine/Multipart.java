package integrationmapper.engine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code multipart/form-data} (RFC 7578) read and written by Patchbay itself (D-29), on bytes, so files pass
 * unchanged. Spring's own multipart handling is off: it would parse first, with a 1 MB file limit, and Camel would
 * then want its attachments library and temp files.
 */
final class Multipart {

    /** One part: its field name, the file name if it is a file, its Content-Type and its bytes. */
    record Part(String name, String fileName, String contentType, byte[] bytes) {
    }

    /** A written body and the Content-Type that names its boundary. */
    record Body(String contentType, byte[] bytes) {
    }

    private static final byte[] CRLF = {'\r', '\n'};

    private Multipart() {
    }

    static boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    /** The parts of a body; throws IllegalArgumentException when it can't be read. */
    static List<Part> read(byte[] body, String contentType) {
        String boundary = parameter(contentType, "boundary");
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("The multipart body could not be read: its Content-Type names no boundary");
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] next = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int at = indexOf(body, delimiter, 0);
        if (at < 0) {
            throw unreadable("no boundary found");
        }
        List<Part> parts = new ArrayList<>();
        at += delimiter.length;
        while (true) {
            if (startsWith(body, at, new byte[] {'-', '-'})) {
                return parts; // the closing delimiter
            }
            if (!startsWith(body, at, CRLF)) {
                throw unreadable("a boundary is not followed by a line break");
            }
            int headersStart = at + 2;
            int headersEnd = indexOf(body, new byte[] {'\r', '\n', '\r', '\n'}, headersStart);
            if (headersEnd < 0) {
                throw unreadable("a part's headers do not end");
            }
            int contentStart = headersEnd + 4;
            int contentEnd = indexOf(body, next, contentStart);
            if (contentEnd < 0) {
                throw unreadable("a part does not end");
            }
            parts.add(part(new String(body, headersStart, headersEnd - headersStart, StandardCharsets.UTF_8),
                    Arrays.copyOfRange(body, contentStart, contentEnd)));
            at = contentEnd + next.length;
        }
    }

    private static Part part(String headers, byte[] bytes) {
        String disposition = null;
        String type = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-disposition")) {
                    disposition = value;
                } else if (name.equals("content-type")) {
                    type = value;
                }
            }
        }
        String name = parameter(disposition, "name");
        if (name == null) {
            throw unreadable("a part has no name");
        }
        return new Part(name, Blobs.fileName(disposition), type, bytes);
    }

    /**
     * The Input a caller sent as multipart: a part named like a file field is that file, one named like another field
     * is its text, typed like a query parameter; parts the Input doesn't have are ignored, like extra JSON keys.
     */
    static ObjectNode toInput(List<Part> parts, PayloadDefinition input) {
        ObjectNode texts = Json.MAPPER.createObjectNode();
        ObjectNode files = Json.MAPPER.createObjectNode();
        for (Part p : parts) {
            var field = input.fields() == null ? null
                    : input.fields().stream().filter(f -> p.name().equals(f.name())).findFirst().orElse(null);
            if (field == null) {
                continue;
            }
            if ("blob".equals(field.type())) {
                files.set(p.name(), Blobs.fromRaw(p.bytes(), p.contentType(), p.fileName() == null ? null
                        : "form-data; " + Blobs.fileNameParameters(p.fileName())));
            } else {
                texts.put(p.name(), new String(p.bytes(), CompiledIntegration.charset(p.contentType())));
            }
        }
        ObjectNode in = InputValidator.coerceQuery(texts, input);
        in.setAll(files);
        return in;
    }

    /**
     * Writes a mapped body object: a file (D-29) as a file part with its name and type, null left out, anything else
     * as a text part.
     */
    static Body write(JsonNode body) {
        String boundary = "patchbay-" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, JsonNode> e : body.properties()) {
            JsonNode v = e.getValue();
            if (v == null || v.isNull() || v.isMissingNode()) {
                continue;
            }
            String disposition = "form-data; name=\"" + e.getKey().replace("\"", "\\\"") + "\"";
            byte[] content;
            String type = null;
            if (v.isObject() && v.path("data").isTextual()) {
                if (v.hasNonNull("fileName")) {
                    disposition += "; " + Blobs.fileNameParameters(v.get("fileName").asText());
                } else {
                    disposition += "; filename=\"" + e.getKey().replace("\"", "\\\"") + "\"";
                }
                type = v.path("contentType").asText(Blobs.DEFAULT_TYPE);
                content = Blobs.bytes(v);
            } else {
                content = (v.isValueNode() ? v.asText() : v.toString()).getBytes(StandardCharsets.UTF_8);
            }
            out.writeBytes(("--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\n"
                    + (type == null ? "" : "Content-Type: " + type + "\r\n") + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.writeBytes(content);
            out.writeBytes(CRLF);
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Body("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    /** A {@code key=value} or {@code key="value"} parameter of a header, null if absent. */
    static String parameter(String header, String key) {
        if (header == null) {
            return null;
        }
        for (String p : header.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().equalsIgnoreCase(key)) {
                String v = p.substring(eq + 1).trim();
                return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
            }
        }
        return null;
    }

    private static IllegalArgumentException unreadable(String why) {
        return new IllegalArgumentException("The multipart body could not be read: " + why);
    }

    private static boolean startsWith(byte[] body, int at, byte[] prefix) {
        if (at + prefix.length > body.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (body[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] body, byte[] needle, int from) {
        outer:
        for (int i = from; i <= body.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (body[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
