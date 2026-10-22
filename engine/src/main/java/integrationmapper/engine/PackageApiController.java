package integrationmapper.engine;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console API for moving integrations between environments as a package (D-28, FR-060). */
@RestController
@RequestMapping("/admin/api")
public class PackageApiController {

    private final Packages packages;

    public PackageApiController(Packages packages) {
        this.packages = packages;
    }

    record ExportRequest(List<String> integrations) {
    }

    /** The picked integrations as a ZIP download. */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody String body) throws Exception {
        ExportRequest req;
        try {
            req = Json.MAPPER.readValue(body, ExportRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body is not valid: " + e.getOriginalMessage());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"patchbay-" + LocalDate.now() + ".zip\"")
                .body(packages.export(req.integrations()));
    }

    /** Stores a package sent as the raw ZIP body; nothing goes live (Make active per integration does that). */
    @PostMapping("/import")
    public ResponseEntity<String> importPackage(@RequestBody byte[] zip) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Json.MAPPER.writeValueAsString(packages.importZip(zip)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad-request");
        body.put("detail", e.getMessage());
        if (e instanceof Packages.PackageException p) {
            body.put("problems", p.problems);
        }
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Json.MAPPER.writeValueAsString(body));
    }
}
