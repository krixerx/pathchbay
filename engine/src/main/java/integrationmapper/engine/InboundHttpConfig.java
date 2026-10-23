package integrationmapper.engine;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * When Patchbay answers before reading a body (a file over {@code mapper.max-blob-bytes} gets 413 at the gate,
 * D-29), Tomcat reads and drops the rest of the body so the caller can read the answer. Its default of 2 MB is
 * raised to twice the file limit; anything larger still has its connection closed, so nobody can make the engine
 * read without end.
 */
@Configuration
public class InboundHttpConfig {

    @Bean
    WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> swallowRefusedBodies(
            @Value("${mapper.max-blob-bytes:52428800}") long maxBlobBytes) {
        return new Customizer((int) Math.min(Integer.MAX_VALUE, 2 * maxBlobBytes));
    }

    /** Runs after Spring Boot's own Tomcat settings, so its default does not win. */
    private record Customizer(int swallow) implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableTomcatWebServerFactory factory) {
            factory.addConnectorCustomizers(connector -> {
                if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http) {
                    http.setMaxSwallowSize(swallow);
                }
            });
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
