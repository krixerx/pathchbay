package integrationmapper.engine;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin calls until the console exists: status, publish a draft, list versions, make a version active. */
@RestController
public class AdminController {

    private final IntegrationRegistry registry;
    private final IntegrationPublisher publisher;
    private final ConfigStore store;

    public AdminController(IntegrationRegistry registry, IntegrationPublisher publisher, ConfigStore store) {
        this.registry = registry;
        this.publisher = publisher;
        this.store = store;
    }

    @GetMapping("/admin/integrations")
    public Map<String, Object> integrations() {
        return Map.of("active", registry.activeVersions(), "broken", registry.brokenReasons());
    }

    @PostMapping("/admin/integrations/{slug}/publish")
    public Map<String, String> publish(@PathVariable String slug) throws Exception {
        return Map.of("integration", slug, "activeVersion", publisher.publishDraft(slug));
    }

    @GetMapping("/admin/integrations/{slug}/versions")
    public Map<String, Object> versions(@PathVariable String slug) throws Exception {
        String active = registry.activeVersions().get(slug);
        return Map.of("integration", slug, "versions", store.versions(slug), "activeVersion", active == null ? "" : active);
    }

    @PostMapping("/admin/integrations/{slug}/versions/{version}/activate")
    public Map<String, String> activate(@PathVariable String slug, @PathVariable String version) throws Exception {
        publisher.activateVersion(slug, version);
        return Map.of("integration", slug, "activeVersion", version);
    }

    /** Publish problems (broken links, missing secret, unknown version) come back as one readable 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().header("Content-Type", "application/json")
                .body(Json.error("publish-failed", null, e.getMessage()));
    }
}
