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

import integrationmapper.engine.testsupport.Admin;

/**
 * INBOUND_API_KEY: integration calls need X-API-Key. The console and the admin API are behind the admin login instead
 * (harden-admin-surface): the key neither opens nor is needed for them.
 */
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
        return status(path, key, null);
    }

    int status(String path, String key, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (key != null) {
            b.header("X-API-Key", key);
        }
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void keyIsRequiredForIntegrations() throws Exception {
        // An unknown integration: without the key 401 comes first, with it the normal 404.
        assertThat(status("/api/v1/integrations/nothing", null)).isEqualTo(401);
        assertThat(status("/api/v1/integrations/nothing", "wrong")).isEqualTo(401);
        assertThat(status("/api/v1/integrations/nothing", "k-inbound")).isEqualTo(404);
    }

    /** The callers' key never opens the admin API; only the admin login does, with or without the key. */
    @Test
    void adminApiNeedsTheAdminLoginNotTheKey() throws Exception {
        assertThat(status("/admin/api/integrations", "k-inbound")).isEqualTo(401);
        assertThat(status("/admin/integrations", "k-inbound")).isEqualTo(401);
        assertThat(status("/admin/api/integrations", null, Admin.AUTHORIZATION)).isEqualTo(200);
        assertThat(status("/admin/api/integrations", "k-inbound", Admin.AUTHORIZATION)).isEqualTo(200);
    }

    @Test
    void consoleAndPublicDocumentsNeedNoKey() throws Exception {
        assertThat(status("/", null, Admin.AUTHORIZATION)).isNotEqualTo(401);
        assertThat(status("/index.html", null, Admin.AUTHORIZATION)).isNotEqualTo(401);
        assertThat(status("/api/openapi.json", null)).isEqualTo(200);
        // Health probes answer without the key (Docker HEALTHCHECK); no other actuator endpoint is open.
        assertThat(status("/actuator/health/readiness", null)).isEqualTo(200);
        assertThat(status("/actuator/health", null)).isEqualTo(200);
        assertThat(status("/actuator/env", null)).isEqualTo(401);
    }

    /** Decided on the path the router sees: decoded and without ;parameters (they used to bypass it). */
    @Test
    void encodedOrParameterisedPathsAreProtectedToo() throws Exception {
        for (String path : new String[] {"/api/v1/%69ntegrations/nothing", "/api/v1/integrations;x/nothing", "/%61pi/v1/integrations/nothing",
                "/assets/../admin/api/integrations", "/assets;x/..;/admin/api/integrations"}) {
            assertThat(status(path, null)).as(path).isEqualTo(401);
        }
        // Admin paths, however spelled, need the admin login.
        for (String path : new String[] {"/%61dmin/api/integrations", "/admin;x/api/integrations", "/admin/api;x/integrations"}) {
            assertThat(status(path, "k-inbound")).as(path).isEqualTo(401);
        }
        assertThat(status("/%61dmin/api/integrations", null, Admin.AUTHORIZATION)).isEqualTo(200);
    }

    /** Deny by default: a path that is neither the console's files, the admin API nor an OpenAPI document needs the key. */
    @Test
    void anyOtherPathNeedsTheKey() throws Exception {
        assertThat(status("/actuator", null)).isEqualTo(401);
        assertThat(status("/error", null)).isEqualTo(401);
    }
}
