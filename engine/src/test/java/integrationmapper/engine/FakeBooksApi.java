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

    /** Answers after 1.5 s, longer than the timeout the version tests configure. */
    @GetMapping("/fake/slow")
    ResponseEntity<String> slow() throws InterruptedException {
        Thread.sleep(1500);
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"id\":1}");
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
