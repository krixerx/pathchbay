package integrationmapper.engine;

import java.util.Map;
import java.util.Set;

import org.apache.camel.CamelContext;
import org.springframework.stereotype.Component;

/**
 * Activates a version: copy the Input/Output definitions from the library, compile (R9), then swap the route
 * behind the gate (R7). Version files
 * and {@code activeVersion} are not written yet (task E4); the definition passed in is what goes live.
 */
@Component
public class IntegrationPublisher {

    private final CamelContext camel;
    private final IntegrationGate gate;
    private final IntegrationRegistry registry;
    private final PayloadLibrary library;

    public IntegrationPublisher(CamelContext camel, IntegrationGate gate, IntegrationRegistry registry, PayloadLibrary library) {
        this.camel = camel;
        this.gate = gate;
        this.registry = registry;
        this.library = library;
    }

    public void publish(ServiceDefinition def) throws Exception {
        String method = def.target().method();
        if (!IntegrationRoutes.METHODS.contains(method)) {
            throw new IllegalArgumentException("Integration " + def.id() + ": unsupported method " + method);
        }
        PayloadDefinition input = library.get(def.input());
        PayloadDefinition output = library.get(def.output());
        if (Set.of("GET", "DELETE").contains(method) && !input.isFlat()) {
            throw new IllegalArgumentException("Integration " + def.id() + ": a " + method
                    + " integration is called with query parameters, so its Input " + input.id() + " must only have simple fields");
        }
        CompiledIntegration compiled = CompiledIntegration.compile(def, input, output);
        String routeId = "int-" + def.id();

        gate.swap(def.id(), () -> {
            if (camel.getRoute(routeId) != null) {
                camel.getRouteController().stopRoute(routeId);
                camel.removeRoute(routeId);
            }
            registry.activate(compiled);
            camel.addRouteFromTemplate(routeId, IntegrationRoutes.templateId(method),
                    Map.of("slug", def.id(), "baseUrl", def.target().baseUrl()));
        });
    }
}
