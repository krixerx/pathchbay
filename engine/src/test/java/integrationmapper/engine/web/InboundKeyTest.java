package integrationmapper.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** INBOUND_API_KEY: integrations and the console API need X-API-Key; the console page itself stays open. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InboundKeyTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        String dir = Files.createTempDirectory("inbound-key").toString();
        r.add("mapper.config-dir", () -> dir);
        r.add("INBOUND_API_KEY", () -> "k-inbound");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    int status(String path, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (key != null) {
            b.header("X-API-Key", key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void keyIsRequiredForIntegrationsAndConsoleApi() throws Exception {
        assertThat(status("/admin/api/integrations", null)).isEqualTo(401);
        assertThat(status("/admin/api/integrations", "wrong")).isEqualTo(401);
        assertThat(status("/admin/api/integrations", "k-inbound")).isEqualTo(200);
        assertThat(status("/admin/integrations", null)).isEqualTo(401);
        // An unknown integration: without the key 401 comes first, with it the normal 404.
        assertThat(status("/api/v1/integrations/nothing", null)).isEqualTo(401);
        assertThat(status("/api/v1/integrations/nothing", "k-inbound")).isEqualTo(404);
    }

    @Test
    void consolePageStaysOpenSoItCanAskForTheKey() throws Exception {
        assertThat(status("/", null)).isNotEqualTo(401);
        assertThat(status("/index.html", null)).isNotEqualTo(401);
        assertThat(status("/favicon.svg", null)).isNotEqualTo(401);
        assertThat(status("/assets/index.js", null)).isNotEqualTo(401);
        assertThat(status("/api/openapi.json", null)).isEqualTo(200);
        // Health probes answer without the key (Docker HEALTHCHECK); no other actuator endpoint is open.
        assertThat(status("/actuator/health/readiness", null)).isEqualTo(200);
        assertThat(status("/actuator/health", null)).isEqualTo(200);
        assertThat(status("/actuator/env", null)).isEqualTo(401);
    }

    /** The key is checked on the path the router sees: decoded and without ;parameters (they used to bypass it). */
    @Test
    void encodedOrParameterisedPathsNeedTheKeyToo() throws Exception {
        for (String path : new String[] {"/%61dmin/api/integrations", "/admin;x/api/integrations", "/admin/api;x/integrations",
                "/api/v1/%69ntegrations/nothing", "/api/v1/integrations;x/nothing", "/%61pi/v1/integrations/nothing",
                "/assets/../admin/api/integrations", "/assets;x/..;/admin/api/integrations"}) {
            assertThat(status(path, null)).as(path).isEqualTo(401);
        }
        assertThat(status("/%61dmin/api/integrations", "k-inbound")).isEqualTo(200);
    }

    /** Deny by default: a path that is neither the console's files nor an OpenAPI document needs the key. */
    @Test
    void anyOtherPathNeedsTheKey() throws Exception {
        assertThat(status("/actuator", null)).isEqualTo(401);
        assertThat(status("/error", null)).isEqualTo(401);
    }
}
