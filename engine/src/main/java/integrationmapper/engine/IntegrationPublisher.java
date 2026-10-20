package integrationmapper.engine;

import java.util.Map;

import org.apache.camel.CamelContext;
import org.springframework.stereotype.Component;

/**
 * Activates a version: compile first (R9), then swap the route behind the gate (R7). Skeleton scope: version files
 * and {@code activeVersion} are not written yet (task E4); the definition passed in is what goes live.
 */
@Component
public class IntegrationPublisher {

    private final CamelContext camel;
    private final IntegrationGate gate;
    private final IntegrationRegistry registry;

    public IntegrationPublisher(CamelContext camel, IntegrationGate gate, IntegrationRegistry registry) {
        this.camel = camel;
        this.gate = gate;
        this.registry = registry;
    }

    public void publish(ServiceDefinition def) throws Exception {
        String method = def.target().method();
        if (!IntegrationRoutes.METHODS.contains(method)) {
            throw new IllegalArgumentException("Integration " + def.id() + ": unsupported method " + method);
        }
        CompiledIntegration compiled = CompiledIntegration.compile(def);
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
