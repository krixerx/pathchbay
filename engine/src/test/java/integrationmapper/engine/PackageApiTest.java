package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Packages through the console API (D-28): export, delete, import, Make active, and the endpoint answers again. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PackageApiTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("package-config");
            Files.createDirectories(CONFIG.resolve("entities"));
            Files.writeString(CONFIG.resolve("entities/book-ref.json"), EngineSkeletonTest.BOOK_REF);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("mapper.config-dir", CONFIG::toString);
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<byte[]> call(String method, String path, String contentType, byte[] body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", contentType)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    JsonNode send(String method, String path, Object body) throws Exception {
        byte[] json = body == null ? null : Json.MAPPER.writeValueAsBytes(body);
        HttpResponse<byte[]> r = call(method, "/admin/api" + path, "application/json", json);
        assertThat(r.statusCode()).as(new String(r.body())).isEqualTo(200);
        return Json.MAPPER.readTree(r.body());
    }

    @Test
    void exportDeleteImportMakeActive() throws Exception {
        send("POST", "/contracts", Map.of("name", "Fake REST API", "content", ContractImporterTest.fixture()));
        send("POST", "/integrations", Map.of("name", "Get book"));
        send("PUT", "/integrations/get-book/payloads", Map.of("input", "book-ref", "output", "book-ref"));
        send("PUT", "/integrations/get-book/operation", Map.of("contract", "fake-rest-api", "operationId", "GET /api/v1/Books/{id}"));
        send("PUT", "/integrations/get-book/links", Map.of(
                "request", List.of(Map.of("from", "br-bookId", "to", "path.id")),
                "response", List.of(Map.of("from", "id", "to", "br-bookId"), Map.of("from", "title", "to", "br-title"))));
        send("PUT", "/connections/fake-rest-api", Map.of("baseUrl", "http://localhost:" + port + "/fake"));
        send("POST", "/integrations/get-book/publish", null);

        HttpResponse<byte[]> exported = call("POST", "/admin/api/export", "application/json",
                Json.MAPPER.writeValueAsBytes(Map.of("integrations", List.of("get-book"))));
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(exported.headers().firstValue("Content-Type")).hasValue("application/zip");
        assertThat(exported.headers().firstValue("Content-Disposition").orElse("")).contains("patchbay-").contains(".zip");
        assertThat(PackagesTest.entries(exported.body())).containsKey("services/get-book.versions/1.0.0.json");

        assertThat(call("DELETE", "/admin/api/integrations/get-book", "application/json", null).statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/v1/integrations/get-book?bookId=7", "application/json", null).statusCode()).isEqualTo(404);

        HttpResponse<byte[]> imported = call("POST", "/admin/api/import", "application/zip", exported.body());
        assertThat(imported.statusCode()).as(new String(imported.body())).isEqualTo(200);
        JsonNode item = Json.MAPPER.readTree(imported.body()).path("integrations").get(0);
        assertThat(item.path("versionsAdded").toString()).isEqualTo("[\"1.0.0\"]");
        assertThat(item.path("exportedActiveVersion").asText()).isEqualTo("1.0.0");
        assertThat(item.path("needs")).isEmpty();
        assertThat(item.path("warnings")).isEmpty();
        assertThat(call("GET", "/api/v1/integrations/get-book?bookId=7", "application/json", null).statusCode())
                .as("an import makes nothing live").isEqualTo(404);

        send("POST", "/integrations/get-book/versions/1.0.0/activate", null);
        HttpResponse<byte[]> live = call("GET", "/api/v1/integrations/get-book?bookId=7", "application/json", null);
        assertThat(new String(live.body())).isEqualTo("{\"bookId\":7,\"title\":\"Book 7\"}");

        HttpResponse<byte[]> refused = call("POST", "/admin/api/import", "application/zip", "nonsense".getBytes());
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(new String(refused.body())).contains("Not a Patchbay package");
    }
}
