package integrationmapper.engine;

import java.util.List;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * One route template per HTTP method of the external operation (D2). Callers use the same method (R1, D16):
 * a JSON body for POST/PUT/PATCH, query parameters for GET/DELETE. Each published integration becomes
 * a route {@code int-<slug>} created from its method's template.
 */
@Component
public class IntegrationRoutes extends RouteBuilder {

    static final List<String> METHODS = List.of("POST", "PUT", "PATCH", "GET", "DELETE");

    private final IntegrationProcessors processors;

    public IntegrationRoutes(IntegrationProcessors processors) {
        this.processors = processors;
    }

    static String templateId(String method) {
        return "integration-" + method.toLowerCase();
    }

    @Override
    public void configure() {
        for (String method : METHODS) {
            routeTemplate(templateId(method))
                    .templateParameter("slug")
                    .templateParameter("baseUrl")
                    .from("platform-http:/api/v1/integrations/{{slug}}?httpMethodRestrict=" + method)
                    .setProperty(IntegrationProcessors.SLUG, constant("{{slug}}"))
                    .doTry()
                        .process(processors::mapRequest)
                        // The full URL comes from the CamelHttpUri header; the endpoint only fixes the host per integration.
                        .to("{{baseUrl}}?throwExceptionOnFailure=false")
                        .process(processors::mapResponse)
                    .doCatch(Exception.class)
                        .process(processors::mapError)
                    .end();
        }
    }
}
