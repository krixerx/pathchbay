package integrationmapper.engine;

import org.apache.camel.CamelContext;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbound timeouts for external calls (runtime rule: 10 seconds). Camel's defaults are 3 minutes, and the Spring
 * Boot starter only exposes properties for the {@code http} component, so both {@code http} and {@code https} are
 * set here. A timeout surfaces as 504 (IntegrationProcessors).
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    CamelContextConfiguration outboundTimeouts(@Value("${mapper.outbound-timeout-ms:10000}") long timeoutMs) {
        return new CamelContextConfiguration() {
            @Override
            public void beforeApplicationStart(CamelContext camel) {
                for (String scheme : new String[] {"http", "https"}) {
                    HttpComponent http = camel.getComponent(scheme, HttpComponent.class);
                    http.setConnectTimeout(timeoutMs);
                    http.setConnectionRequestTimeout(timeoutMs);
                    http.setResponseTimeout(timeoutMs);
                    http.setSoTimeout(timeoutMs);
                }
            }

            @Override
            public void afterApplicationStart(CamelContext camel) {
            }
        };
    }
}
