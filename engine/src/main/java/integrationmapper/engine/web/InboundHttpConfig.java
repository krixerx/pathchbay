package integrationmapper.engine.web;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import integrationmapper.engine.config.MapperProperties;

/**
 * When Patchbay answers before reading a body (a file over {@code mapper.max-blob-bytes} gets 413 at the gate,
 * D-29), Tomcat reads and drops the rest of the body so the caller can read the answer. Its default of 2 MB is
 * raised to twice the file limit; anything larger still has its connection closed, so nobody can make the engine
 * read without end.
 */
@Configuration
public class InboundHttpConfig {

    @Bean
    WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> swallowRefusedBodies(MapperProperties properties) {
        return new Customizer((int) Math.min(Integer.MAX_VALUE, 2 * properties.maxBlobBytes()));
    }

    /**
     * Camel's platform-http answers integration calls asynchronously. The request must not time out (503) before the
     * external call can: its connection-pool, connect and response timeouts can add up to three outbound timeouts.
     */
    @Bean
    WebMvcConfigurer asyncTimeoutAboveTheOutboundBudget(MapperProperties properties) {
        long outboundTimeoutMs = properties.outboundTimeoutMs();
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setDefaultTimeout(3 * outboundTimeoutMs + 30_000);
            }
        };
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
