package integrationmapper.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The shared payload definitions (D-8). Publishing copies the definitions an integration uses into its compiled
 * version, so later library edits never change a live version (D-9).
 */
@Component
public class PayloadLibrary {

    private final Map<String, PayloadDefinition> definitions = new ConcurrentHashMap<>();

    public void put(PayloadDefinition def) {
        def.check();
        definitions.put(def.id(), def);
    }

    /** All definitions, sorted by id. */
    public List<PayloadDefinition> all() {
        return definitions.values().stream().sorted(Comparator.comparing(PayloadDefinition::id)).toList();
    }

    public PayloadDefinition get(String id) {
        PayloadDefinition def = definitions.get(id);
        if (def == null) {
            throw new IllegalArgumentException("Unknown payload definition " + id);
        }
        return def;
    }
}
