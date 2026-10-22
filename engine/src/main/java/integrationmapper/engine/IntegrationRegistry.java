package integrationmapper.engine;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** The compiled version each live route serves, plus integrations skipped at startup with their reason (R10). */
@Component
public class IntegrationRegistry {

    private final Map<String, CompiledIntegration> active = new ConcurrentHashMap<>();
    private final Map<String, String> broken = new ConcurrentHashMap<>();

    CompiledIntegration get(String slug) {
        CompiledIntegration ci = active.get(slug);
        if (ci == null) {
            throw new IllegalStateException("No active version for integration " + slug);
        }
        return ci;
    }

    void activate(CompiledIntegration ci) {
        active.put(ci.definition().id(), ci);
        broken.remove(ci.definition().id());
    }

    void remove(String slug) {
        active.remove(slug);
        broken.remove(slug);
    }

    void markBroken(String slug, String reason) {
        broken.put(slug, reason);
    }

    Map<String, String> activeVersions() {
        Map<String, String> m = new TreeMap<>();
        active.forEach((slug, ci) -> m.put(slug, ci.definition().version()));
        return m;
    }

    Map<String, String> brokenReasons() {
        return new TreeMap<>(broken);
    }
}
