package integrationmapper.engine;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Skeleton status view: which integrations serve which version, and which were skipped and why. */
@RestController
public class AdminController {

    private final IntegrationRegistry registry;

    public AdminController(IntegrationRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/admin/integrations")
    public Map<String, Object> integrations() {
        return Map.of("active", registry.activeVersions(), "broken", registry.brokenReasons());
    }
}
