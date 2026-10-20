package spike.routing;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the plan's "POST template": one inbound endpoint per integration.
 * The body only echoes slug and version so a caller can tell old and new routes apart;
 * mapping and the outbound call are out of scope for E1.
 */
@Component
public class PostTemplate extends RouteBuilder {

    @Override
    public void configure() {
        routeTemplate("postTemplate")
                .templateParameter("slug")
                .templateParameter("version")
                .from("platform-http:/api/v1/integrations/{{slug}}?httpMethodRestrict=POST")
                .setHeader("Content-Type", constant("application/json"))
                .setBody(simple("{\"slug\":\"{{slug}}\",\"version\":\"{{version}}\"}"));
    }
}
