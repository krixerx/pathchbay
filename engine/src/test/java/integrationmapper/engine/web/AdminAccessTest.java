package integrationmapper.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import integrationmapper.engine.testsupport.Admin;

/**
 * The console's and admin API's edge (harden-admin-surface): Basic login, no changes from other sites, JSON or ZIP
 * bodies only, body limits and browser protections. The engine runs with the test admin login and no inbound key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAccessTest {

    static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        dir = Files.createTempDirectory("admin-access");
        r.add("mapper.config-dir", () -> dir.toString());
        r.add("mapper.max-body-bytes", () -> "20000");
        r.add("server.forward-headers-strategy", () -> "native");
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withoutTheLoginTheBrowserIsAskedForIt() throws Exception {
        for (String path : new String[] {"/", "/index.html", "/admin/api/integrations", "/admin/integrations", "/%61dmin/api/integrations"}) {
            HttpResponse<String> r = send(request(path));
            assertThat(r.statusCode()).as(path).isEqualTo(401);
            assertThat(r.headers().firstValue("WWW-Authenticate")).as(path).hasValue("Basic realm=\"Patchbay\", charset=\"UTF-8\"");
        }
        assertThat(send(request("/admin/api/integrations").header("Authorization", Admin.basic(Admin.USER, "wrong-password"))).statusCode())
                .isEqualTo(401);
        assertThat(send(request("/admin/api/integrations").header("Authorization", Admin.basic("someone", Admin.PASSWORD))).statusCode())
                .isEqualTo(401);
        assertThat(send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)).statusCode()).isEqualTo(200);
        // Integration calls and the public documents are not behind the admin login.
        assertThat(send(request("/api/openapi.json")).statusCode()).isEqualTo(200);
        assertThat(send(request("/api/v1/integrations/nothing")).statusCode()).isEqualTo(404);
    }

    /** Reproduced in the review: a text/plain POST from another site created a draft. */
    @Test
    void anotherSiteCannotCreateADraftEvenWithTheBrowsersLogin() throws Exception {
        HttpResponse<String> r = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "text/plain").header("Origin", "http://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"csrf probe\"}")));
        assertThat(r.statusCode()).isIn(403, 415);
        // Same-site origin, but still a type a form can send.
        HttpResponse<String> plain = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"csrf probe\"}")));
        assertThat(plain.statusCode()).isEqualTo(415);
        assertThat(plain.body()).contains("unsupported-media-type").contains("application/json");
        assertThat(Files.exists(dir.resolve("services/csrf-probe.json"))).isFalse();
    }

    /** A POST without a body needs no preflight, so the Origin check is what stops it. */
    @Test
    void anotherSiteCannotPublishWithoutABody() throws Exception {
        HttpResponse<String> r = send(request("/admin/api/integrations/nothing/publish").header("Authorization", Admin.AUTHORIZATION)
                .header("Origin", "http://evil.example").POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("cross-site");
        HttpResponse<String> fetchMetadata = send(request("/admin/integrations/nothing/publish").header("Authorization", Admin.AUTHORIZATION)
                .header("Sec-Fetch-Site", "cross-site").POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(fetchMetadata.statusCode()).isEqualTo(403);
    }

    @Test
    void theConsoleAndScriptsCanStillWrite() throws Exception {
        HttpResponse<String> console = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json").header("Origin", "http://localhost:" + port)
                .header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"from console\"}")));
        assertThat(console.statusCode()).as(console.body()).isEqualTo(200);
        HttpResponse<String> script = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"from script\"}")));
        assertThat(script.statusCode()).as(script.body()).isEqualTo(200);
    }

    /** Behind a trusted proxy the browser's Origin is the proxy's public address. */
    @Test
    void anOriginMatchingTheForwardedHostIsAllowed() throws Exception {
        HttpResponse<String> r = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json").header("Origin", "https://patchbay.example")
                .header("X-Forwarded-Host", "patchbay.example").header("X-Forwarded-Proto", "https")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"behind proxy\"}")));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        HttpResponse<String> other = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json").header("Origin", "https://evil.example")
                .header("X-Forwarded-Host", "patchbay.example").header("X-Forwarded-Proto", "https")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"behind proxy 2\"}")));
        assertThat(other.statusCode()).isEqualTo(403);
    }

    @Test
    void aPackageMustBeSentAsZip() throws Exception {
        HttpResponse<String> r = send(request("/admin/api/import").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {'P', 'K', 3, 4})));
        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(r.body()).contains("application/zip");
    }

    /** Reproduced in the review: a 400 MB package filled the heap. Here the JSON limit is 20000 bytes. */
    @Test
    void adminBodiesOverTheLimitAre413EvenWhenChunked() throws Exception {
        byte[] big = ("{\"name\":\"" + "x".repeat(30_000) + "\"}").getBytes();
        HttpResponse<String> declared = send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(big)));
        assertThat(declared.statusCode()).isEqualTo(413);
        HttpResponse<String> chunked = send(request("/admin/api/definitions/x").header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big))));
        assertThat(chunked.statusCode()).isEqualTo(413);
        assertThat(chunked.body()).contains("payload-too-large");
        assertThat(Files.exists(dir.resolve("entities/x.json"))).isFalse();
        // The engine keeps serving.
        assertThat(send(request("/admin/api/integrations").header("Authorization", Admin.AUTHORIZATION)).statusCode()).isEqualTo(200);
    }

    @Test
    void consoleAndAdminAnswersCarryBrowserProtections() throws Exception {
        for (String path : new String[] {"/", "/admin/api/integrations"}) {
            HttpResponse<String> r = send(request(path).header("Authorization", Admin.AUTHORIZATION));
            assertThat(r.headers().firstValue("Content-Security-Policy")).as(path)
                    .hasValueSatisfying(csp -> assertThat(csp).contains("default-src 'self'").contains("frame-ancestors 'none'"));
            assertThat(r.headers().firstValue("X-Content-Type-Options")).as(path).hasValue("nosniff");
            assertThat(r.headers().firstValue("Referrer-Policy")).as(path).hasValue("no-referrer");
        }
        // Also on the 401 that asks for the login.
        assertThat(send(request("/assets/index.js")).headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    }
}
