package integrationmapper.engine;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Files (D-29) as Patchbay holds them: the JSON object {@code {contentType, fileName, size, data}} with the bytes
 * as base64 in {@code data}. Blobs are only top-level fields of a payload definition.
 */
final class Blobs {

    static final String DEFAULT_TYPE = "application/octet-stream";

    /** Refuses a file over {@code mapper.max-blob-bytes}; the caller gets 413. */
    static final class TooLargeException extends RuntimeException {
        TooLargeException(String message) {
            super(message);
        }
    }

    private Blobs() {
    }

    /** Problems of a caller's file value; {@code size} and unknown keys are ignored, Patchbay works the size out. */
    static void check(JsonNode v, String path, List<String> problems) {
        if (!v.isObject()) {
            problems.add(path + ": must be a file {fileName, contentType, data}");
            return;
        }
        JsonNode data = v.get("data");
        if (data == null || data.isNull()) {
            problems.add(path + ".data: required");
        } else if (!data.isTextual() || decodedSize(data.asText()) < 0) {
            problems.add(path + ".data: must be base64");
        }
        for (String text : List.of("fileName", "contentType")) {
            JsonNode t = v.get(text);
            if (t != null && !t.isNull() && !t.isTextual()) {
                problems.add(path + "." + text + ": must be text");
            }
        }
    }

    /**
     * Completes every file of {@code obj} after validation: {@code size} from the data (a sent size is replaced) and
     * the default content type. Throws {@link TooLargeException} for a file over {@code maxBytes}.
     */
    static void complete(ObjectNode obj, PayloadDefinition def, long maxBytes) {
        for (PayloadDefinition.Field f : def.fields() == null ? List.<PayloadDefinition.Field>of() : def.fields()) {
            if ("blob".equals(f.type()) && obj.get(f.name()) instanceof ObjectNode file) {
                long size = decodedSize(file.path("data").asText(""));
                if (size < 0) {
                    throw new IllegalArgumentException(f.name() + ".data: must be base64");
                }
                if (size > maxBytes) {
                    throw new TooLargeException(f.name() + " is " + size + " bytes, more than the limit of " + maxBytes + " bytes");
                }
                file.put("size", size);
                if (!file.hasNonNull("contentType")) {
                    file.put("contentType", DEFAULT_TYPE);
                }
            }
        }
    }

    /**
     * The number of bytes base64 text decodes to, or -1 when it is not base64 (standard alphabet, padding optional).
     * Checks without decoding, so a large file is not copied.
     */
    static long decodedSize(String b64) {
        int n = b64.length();
        int pad = n > 0 && b64.charAt(n - 1) == '=' ? (n > 1 && b64.charAt(n - 2) == '=' ? 2 : 1) : 0;
        int chars = n - pad;
        if (pad > 0 && n % 4 != 0 || chars % 4 == 1) {
            return -1;
        }
        for (int i = 0; i < chars; i++) {
            char c = b64.charAt(i);
            boolean ok = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/';
            if (!ok) {
                return -1;
            }
        }
        return chars * 3L / 4;
    }

    /** Bytes of a validated file's data. */
    static byte[] bytes(JsonNode file) {
        return Base64.getDecoder().decode(file.path("data").asText(""));
    }

    /** How many file fields a definition has. */
    static long fileCount(PayloadDefinition def) {
        return def.fields() == null ? 0 : def.fields().stream().filter(f -> "blob".equals(f.type())).count();
    }

    /** The only file field of a definition, or null when it has none or several (a raw body needs exactly one). */
    static PayloadDefinition.Field onlyFile(PayloadDefinition def) {
        List<PayloadDefinition.Field> files = def.fields() == null ? List.of()
                : def.fields().stream().filter(f -> "blob".equals(f.type())).toList();
        return files.size() == 1 ? files.get(0) : null;
    }

    /** A raw body as a file: its bytes, its Content-Type and the file name from its Content-Disposition. */
    static ObjectNode fromRaw(byte[] bytes, String contentType, String contentDisposition) {
        ObjectNode file = Json.MAPPER.createObjectNode();
        String name = fileName(contentDisposition);
        if (name != null) {
            file.put("fileName", name);
        }
        if (contentType != null && !contentType.isBlank()) {
            file.put("contentType", contentType);
        }
        return file.put("data", Base64.getEncoder().encodeToString(bytes == null ? new byte[0] : bytes));
    }

    /** The file name of a Content-Disposition header: {@code filename*} (RFC 5987) wins over {@code filename}; null if none. */
    static String fileName(String contentDisposition) {
        if (contentDisposition == null) {
            return null;
        }
        String plain = null;
        for (String part : contentDisposition.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim().toLowerCase();
            String value = part.substring(eq + 1).trim();
            if (key.equals("filename*")) {
                int quote = value.indexOf("''");
                if (quote >= 0) {
                    return java.net.URLDecoder.decode(value.substring(quote + 2).replace("+", "%2B"),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            } else if (key.equals("filename")) {
                plain = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                        ? value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                        : value;
            }
        }
        return plain;
    }

    /**
     * A Content-Disposition for a file name (RFC 6266): a quoted ASCII {@code filename}, plus {@code filename*} in
     * UTF-8 when the name has other characters.
     */
    static String contentDisposition(String fileName) {
        return "attachment; " + fileNameParameters(fileName);
    }

    /** {@code filename="…"}, plus {@code filename*=UTF-8''…} when the name is not plain ASCII (RFC 6266). */
    static String fileNameParameters(String fileName) {
        StringBuilder ascii = new StringBuilder();
        boolean other = false;
        for (char c : fileName.toCharArray()) {
            if (c < 0x20 || c > 0x7e) {
                ascii.append('_');
                other = true;
            } else {
                ascii.append(c == '"' || c == '\\' ? "\\" + c : String.valueOf(c));
            }
        }
        String header = "filename=\"" + ascii + "\"";
        if (!other) {
            return header;
        }
        StringBuilder encoded = new StringBuilder();
        for (byte b : fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "!#$&+-.^_`|~".indexOf(c) >= 0)) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return header + "; filename*=UTF-8''" + encoded;
    }

    /** A file answer as the call log shows it, {@code {"$body": {…, "data": "<n bytes, base64>"}}}, without encoding it. */
    static JsonNode describe(byte[] bytes, String contentType, String contentDisposition) {
        ObjectNode file = fromRaw(new byte[0], contentType, contentDisposition);
        file.put("size", bytes.length).put("data", "<" + bytes.length + " bytes, base64>");
        return Json.MAPPER.createObjectNode().set(LinkRules.WHOLE_ANSWER, file);
    }

    /** A file-shaped object: text {@code data} next to a {@code fileName}, {@code contentType} or {@code size}. */
    static boolean isFile(JsonNode v) {
        return v.isObject() && v.path("data").isTextual()
                && (v.has("fileName") || v.has("contentType") || v.has("size"));
    }

    /**
     * A copy for the call log and the Test drawer (D-22): every file-shaped object shows its size instead of its
     * data, so a log entry never holds a whole file.
     */
    static JsonNode forLog(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (isFile(value)) {
            ObjectNode copy = ((ObjectNode) value).deepCopy();
            long size = decodedSize(value.path("data").asText());
            copy.put("data", size < 0 ? "<not base64>" : "<" + size + " bytes, base64>");
            return copy;
        }
        if (value.isObject()) {
            ObjectNode copy = Json.MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> e : value.properties()) {
                copy.set(e.getKey(), forLog(e.getValue()));
            }
            return copy;
        }
        if (value.isArray()) {
            var copy = Json.MAPPER.createArrayNode();
            value.forEach(item -> copy.add(forLog(item)));
            return copy;
        }
        return value;
    }
}
