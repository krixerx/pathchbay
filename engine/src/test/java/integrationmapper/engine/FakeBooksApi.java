package integrationmapper.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Stand-in for FakeRESTApi's Books endpoints, served by the test app itself; remembers what it received. */
@RestController
class FakeBooksApi {

    final Map<String, Object> lastRequest = new ConcurrentHashMap<>();

    @PostMapping("/fake/api/v1/Books")
    ResponseEntity<String> create(@RequestBody String body, @RequestHeader HttpHeaders headers) throws Exception {
        lastRequest.put("body", body);
        lastRequest.put("headers", headers);
        JsonNode in = Json.MAPPER.readTree(body);
        var out = Json.MAPPER.createObjectNode().put("id", 99);
        out.set("title", in.get("title"));
        out.set("pageCount", in.get("pageCount"));
        out.put("extra", "x");
        return ResponseEntity.ok().header("Content-Type", "application/json").header("X-Vendor-Secret", "leak-me-not")
                .body(out.toString());
    }

    /** Echoes nothing useful; records headers and query string so tests can see what auth reached the vendor. */
    @GetMapping("/fake/secure")
    ResponseEntity<String> secure(@RequestHeader HttpHeaders headers, jakarta.servlet.http.HttpServletRequest request) {
        lastRequest.put("headers", headers);
        lastRequest.put("query", request.getQueryString() == null ? "" : request.getQueryString());
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"id\":1,\"title\":\"secure\"}");
    }

    /** Takes a file as the raw body (D-29) and remembers its exact bytes and headers. */
    @org.springframework.web.bind.annotation.PutMapping("/fake/files/{id}")
    ResponseEntity<String> putFile(@PathVariable String id, @RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
        lastRequest.put("bytes", body);
        lastRequest.put("headers", headers);
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"documentId\":\"" + id + "\",\"status\":\"STORED\"}");
    }

    /** Every byte value once, so nothing may pass through text unnoticed. */
    static final byte[] ALL_BYTES = new byte[256];

    static {
        for (int i = 0; i < ALL_BYTES.length; i++) {
            ALL_BYTES[i] = (byte) i;
        }
    }

    /**
     * A file as the raw answer (D-29). {@code big}: 1500 bytes; {@code json}: a JSON answer instead of a file;
     * {@code missing}: 404 with a binary body; anything else: {@link #ALL_BYTES} as a PDF named a.pdf.
     */
    @GetMapping("/fake/files/{id}/content")
    ResponseEntity<byte[]> getFile(@PathVariable String id) {
        return switch (id) {
            case "big" -> ResponseEntity.ok().header("Content-Type", "application/octet-stream").body(new byte[1500]);
            case "json" -> ResponseEntity.ok().header("Content-Type", "application/json").body("{\"note\":\"no file\"}".getBytes());
            case "missing" -> ResponseEntity.status(404).header("Content-Type", "application/octet-stream").body(new byte[] {0, 1, 2, (byte) 255});
            default -> ResponseEntity.ok().header("Content-Type", "application/pdf")
                    .header("Content-Disposition", "attachment; filename=\"a.pdf\"").body(ALL_BYTES);
        };
    }

    /** Takes a multipart upload (D-29) and remembers its raw bytes and Content-Type, to be read back by the test. */
    @PostMapping("/fake/upload")
    ResponseEntity<String> upload(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
        lastRequest.put("bytes", body);
        lastRequest.put("headers", headers);
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"documentId\":\"UP-1\",\"status\":\"STORED\"}");
    }

    /** The same file as base64 inside JSON. */
    @GetMapping("/fake/files/{id}/base64")
    ResponseEntity<String> getFileBase64(@PathVariable String id) {
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"fileName\":\"b.pdf\",\"contentType\":\"application/pdf\","
                + "\"contentBase64\":\"" + java.util.Base64.getEncoder().encodeToString(ALL_BYTES) + "\"}");
    }

    final java.util.concurrent.atomic.AtomicInteger busyCalls = new java.util.concurrent.atomic.AtomicInteger();

    /** Always 503 with Retry-After, the answer an HTTP client retries by default; counts how often it was called. */
    @GetMapping("/fake/busy")
    ResponseEntity<String> busy() {
        busyCalls.incrementAndGet();
        return ResponseEntity.status(503).header("Retry-After", "1").header("Content-Type", "text/plain").body("maintenance");
    }

    /** Sets a cookie on every answer and records the headers it got, to see whether a cookie comes back. */
    @GetMapping("/fake/cookie")
    ResponseEntity<String> cookie(@RequestHeader HttpHeaders headers) {
        lastRequest.put("headers", headers);
        return ResponseEntity.ok().header("Content-Type", "application/json").header("Set-Cookie", "vendor=1; Path=/")
                .body("{\"id\":1,\"title\":\"cookie\"}");
    }

    /** Answers after 1.5 s, longer than the timeout the version tests configure. */
    @GetMapping("/fake/slow")
    ResponseEntity<String> slow() throws InterruptedException {
        Thread.sleep(1500);
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"id\":1}");
    }

    /** An XML flavour of create (D-24): takes a Book element, answers one. */
    @PostMapping("/fake/xml/Books")
    ResponseEntity<String> createXml(@RequestBody String body, @RequestHeader HttpHeaders headers) throws Exception {
        lastRequest.put("body", body);
        lastRequest.put("headers", headers);
        JsonNode in = new com.fasterxml.jackson.dataformat.xml.XmlMapper().readTree(body);
        return ResponseEntity.ok().header("Content-Type", "application/xml")
                .body("<Book id=\"99\"><title>" + in.path("title").asText() + "</title><pageCount>" + in.path("pageCount").asText()
                        + "</pageCount></Book>");
    }

    /** An XML list of {@code count} books; one book is a single element, which XML can't tell from a non-list. */
    @GetMapping("/fake/xml/Books")
    ResponseEntity<String> listXml(@org.springframework.web.bind.annotation.RequestParam int count) {
        StringBuilder sb = new StringBuilder("<Books>");
        for (int i = 1; i <= count; i++) {
            sb.append("<Book><id>").append(i).append("</id><title>Book ").append(i).append("</title></Book>");
        }
        return ResponseEntity.ok().header("Content-Type", "text/xml;charset=UTF-8").body(sb.append("</Books>").toString());
    }

    @GetMapping("/fake/api/v1/Books/{id}")
    ResponseEntity<String> get(@PathVariable String id, @RequestHeader HttpHeaders headers) {
        lastRequest.put("path", id);
        lastRequest.put("headers", headers);
        if ("404".equals(id)) {
            return ResponseEntity.status(404).header("Content-Type", "application/json").body("{\"title\":\"Not Found\"}");
        }
        return ResponseEntity.ok().header("Content-Type", "application/json")
                .body("{\"id\":" + id + ",\"title\":\"Book " + id + "\",\"pageCount\":" + id + "00}");
    }
}
