package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Steps 2 and 3 and the Settings page over HTTP, against a real config folder. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContractApiTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("contract-config");
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

    HttpResponse<String> send(String method, String path, Object body) throws Exception {
        String json = body == null ? null : body instanceof String s ? s : Json.MAPPER.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api" + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void importChooseOperationAndSettings() throws Exception {
        HttpResponse<String> imported = send("POST", "/contracts",
                java.util.Map.of("name", "Fake REST API", "content", ContractImporterTest.fixture()));
        assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(imported.body()).path("id").asText()).isEqualTo("fake-rest-api");
        assertThat(send("GET", "/contracts", null).body()).contains("\"id\":\"fake-rest-api\"");

        send("POST", "/integrations", java.util.Map.of("name", "Get book"));
        HttpResponse<String> chosen = send("PUT", "/integrations/get-book/operation",
                java.util.Map.of("contract", "fake-rest-api", "operationId", "GET /api/v1/Books/{id}"));
        assertThat(chosen.statusCode()).as(chosen.body()).isEqualTo(200);
        JsonNode draft = Json.MAPPER.readTree(chosen.body());
        assertThat(draft.path("target").path("method").asText()).isEqualTo("GET");
        assertThat(draft.path("target").path("path").asText()).isEqualTo("/api/v1/Books/{id}");
        assertThat(draft.path("target").has("baseUrl")).as("no absolute server in this contract").isFalse();

        assertThat(send("PUT", "/integrations/get-book/settings", java.util.Map.of("baseUrl", "ftp://x")).body())
                .contains("must start with http");
        HttpResponse<String> saved = send("PUT", "/integrations/get-book/settings", """
                {"baseUrl":"https://fakerestapi.azurewebsites.net","auth":{"type":"bearer"},"secret":"top-secret"}""");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        JsonNode settings = Json.MAPPER.readTree(saved.body());
        assertThat(settings.path("secretInFile").asBoolean()).isTrue();
        assertThat(settings.path("secretEnvName").asText()).isEqualTo("MAPPER_SECRET_GET_BOOK");
        assertThat(saved.body()).doesNotContain("top-secret");
        assertThat(Files.readString(CONFIG.resolve("services/get-book.json"))).doesNotContain("top-secret").contains("bearer");
        assertThat(Files.readString(CONFIG.resolve("secrets/get-book.json"))).contains("top-secret");

        assertThat(send("PUT", "/integrations/get-book/operation",
                java.util.Map.of("contract", "fake-rest-api", "operationId", "nope")).body()).contains("has no operation nope");
    }
}
