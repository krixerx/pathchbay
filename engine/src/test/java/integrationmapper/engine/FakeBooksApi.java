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
        return ResponseEntity.ok().header("Content-Type", "application/json").header("X-Vendor-Secret", "leak-me-not")
                .body("{\"id\":99,\"title\":" + in.path("title") + ",\"pageCount\":" + in.path("pageCount") + ",\"extra\":\"x\"}");
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
