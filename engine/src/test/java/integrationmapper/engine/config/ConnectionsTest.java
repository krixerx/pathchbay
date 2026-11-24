package integrationmapper.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import integrationmapper.engine.format.Json;
import integrationmapper.engine.model.ServiceDefinition;
import integrationmapper.engine.runtime.EngineSkeletonTest;
import integrationmapper.engine.runtime.IntegrationPublisher;

/**
 * Connections (D-26): the base URL is set once per Swagger configuration and environment, never stored in
 * integrations or versions; drafts from before are moved over at startup, and a changed URL reaches live versions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectionsTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("connections-config");
            Files.createDirectories(CONFIG.resolve("entities"));
            Files.createDirectories(CONFIG.resolve("services/old-book.versions"));
            Files.writeString(CONFIG.resolve("entities/book-ref.json"), EngineSkeletonTest.BOOK_REF);
            // A draft and its live version from before connections: both carry the base URL literally.
            String target = "{\"baseUrl\":\"http://localhost:%d/fake\",\"method\":\"GET\",\"path\":\"/api/v1/Books/{id}\"}";
            String links = """
                    "request":{"links":[{"from":"br-bookId","to":"path.id"}]},
                    "response":{"links":[{"from":"id","to":"br-bookId"},{"from":"title","to":"br-title"}]}""";
            Files.writeString(CONFIG.resolve("services/old-book.json"), """
                    {"id":"old-book","contract":"books-api","operationId":"GET /api/v1/Books/{id}","input":"book-ref","output":"book-ref",
                     "target":%s,%s,"activeVersion":"1.0.0"}""".formatted(target, links));
            Files.writeString(CONFIG.resolve("services/old-book.versions/1.0.0.json"), """
                    {"version":"1.0.0","service":{"id":"old-book","version":"1.0.0","input":"book-ref","output":"book-ref",
                     "target":%s,%s},
                     "input":%s,"output":%s}""".formatted(target, links, EngineSkeletonTest.BOOK_REF, EngineSkeletonTest.BOOK_REF));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static int port = freePort();

    static int freePort() {
        try (var s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws IOException {
        // The files above need the port before the app starts, so the test picks it.
        for (Path p : new Path[] {CONFIG.resolve("services/old-book.json"), CONFIG.resolve("services/old-book.versions/1.0.0.json")}) {
            Files.writeString(p, Files.readString(p).replace("%d", String.valueOf(port)));
        }
        r.add("mapper.config-dir", CONFIG::toString);
        r.add("server.port", () -> port);
    }

    @LocalServerPort
    int localPort;

    @Autowired
    IntegrationPublisher publisher;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + localPort + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void oldDraftsMoveToConnectionsAndAChangedUrlReachesLiveVersions() throws Exception {
        // Startup moved the draft's base URL into the connection; the version file was left alone and still serves.
        JsonNode draft = Json.MAPPER.readTree(CONFIG.resolve("services/old-book.json").toFile());
        assertThat(draft.path("target").path("connection").asText()).isEqualTo("books-api");
        assertThat(draft.path("target").has("baseUrl")).isFalse();
        assertThat(Files.readString(CONFIG.resolve("connections/books-api.json"))).contains("http://localhost:" + port + "/fake");
        assertThat(Files.readString(CONFIG.resolve("services/old-book.versions/1.0.0.json"))).contains("\"baseUrl\"");
        assertThat(send("GET", "/api/v1/integrations/old-book?bookId=7", null).body()).isEqualTo("{\"bookId\":7,\"title\":\"Book 7\"}");

        // Publishing the moved draft: the new version names the connection, not the address.
        publisher.publishDraft("old-book");
        String v2 = Files.readString(CONFIG.resolve("services/old-book.versions/1.1.0.json"));
        assertThat(v2).contains("\"connection\" : \"books-api\"").doesNotContain("baseUrl");

        // Pointing the connection elsewhere switches the live version at once, without a publish.
        send("PUT", "/admin/api/connections/books-api", "{\"baseUrl\":\"http://localhost:" + port + "/nowhere\"}");
        assertThat(send("GET", "/api/v1/integrations/old-book?bookId=7", null).statusCode()).isEqualTo(404);
        send("PUT", "/admin/api/connections/books-api", "{\"baseUrl\":\"http://localhost:" + port + "/fake\"}");
        assertThat(send("GET", "/api/v1/integrations/old-book?bookId=7", null).statusCode()).isEqualTo(200);

        // A connection that live integrations call through can't lose its URL.
        HttpResponse<String> cleared = send("PUT", "/admin/api/connections/books-api", "{\"baseUrl\":\"\"}");
        assertThat(cleared.statusCode()).isEqualTo(400);
        assertThat(cleared.body()).contains("old-book");
    }

    @Test
    void aBaseUrlMustBeAPlainHttpUrl() {
        Connections.check("https://api.example.org/v1");
        Connections.check("http://sap.example.org:8000/sap/bc/srt/rfc/sap/zperson?sap-client=100");
        Connections.check("http://my_service:8080/api");
        assertThatThrownBy(() -> Connections.check("http://user:pw@my_service:8080")).hasMessageContaining("user name or password");
        assertThatThrownBy(() -> Connections.check("ftp://api.example.org")).hasMessageContaining("http:// or https://");
        assertThatThrownBy(() -> Connections.check("https://api.example.org/{{secret}}")).hasMessageContaining("not a valid URL");
        assertThatThrownBy(() -> Connections.check("https://user:pw@api.example.org")).hasMessageContaining("user name or password");
        assertThatThrownBy(() -> Connections.check("https://api.example.org/v1#top")).hasMessageContaining("fragment");
        assertThatThrownBy(() -> Connections.check("https:///v1")).hasMessageContaining("no host");
    }

    @Test
    void theEnvironmentWinsOverTheFile(@TempDir Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir.toString());
        Connections fileOnly = new Connections(new MockEnvironment(), store);
        fileOnly.save("books-api", "https://test.example.org");
        assertThat(fileOnly.baseUrl("books-api")).isEqualTo("https://test.example.org");

        Connections withEnv = new Connections(new MockEnvironment().withProperty("MAPPER_URL_BOOKS_API", "https://live.example.org"), store);
        assertThat(withEnv.baseUrl("books-api")).isEqualTo("https://live.example.org");

        ServiceDefinition def = new ServiceDefinition("x", "1.0.0", null, "in", "out",
                new ServiceDefinition.Target(null, "GET", "/", "nothing-set"), null, null, null);
        assertThatThrownBy(() -> fileOnly.resolve(def)).hasMessageContaining("MAPPER_URL_NOTHING_SET");
    }
}
