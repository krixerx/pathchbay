package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The mapping board's API: board data, link autosave, test runs with the mock and against a real (local) system. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MappingApiTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("mapping-config");
            Files.createDirectories(CONFIG.resolve("entities"));
            Files.writeString(CONFIG.resolve("entities/new-book.json"), """
                    {"id":"new-book","fields":[{"id":"nb-title","name":"title","type":"text","required":true},
                      {"id":"nb-pages","name":"pages","type":"integer"}]}""");
            Files.writeString(CONFIG.resolve("entities/book-ref.json"), """
                    {"id":"book-ref","fields":[{"id":"br-id","name":"bookId","type":"integer"},{"id":"br-title","name":"title","type":"text"}]}""");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("mapper.config-dir", CONFIG::toString);
        r.add("MAPPER_SECRET_ADD_BOOK", () -> "k-123");
    }

    @LocalServerPort
    int port;

    @Autowired
    FakeBooksApi fake;

    final HttpClient http = HttpClient.newHttpClient();

    JsonNode send(String method, String path, Object body) throws Exception {
        String json = body == null ? null : body instanceof String s ? s : Json.MAPPER.writeValueAsString(body);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api" + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body());
    }

    @Test
    void boardLinksAndTestRuns() throws Exception {
        send("POST", "/contracts", Map.of("name", "Fake REST API", "content", ContractImporterTest.fixture()));
        send("POST", "/integrations", Map.of("name", "Add book"));
        send("PUT", "/integrations/add-book/payloads", Map.of("input", "new-book", "output", "book-ref"));
        send("PUT", "/integrations/add-book/operation", Map.of("contract", "fake-rest-api", "operationId", "POST /api/v1/Books"));

        JsonNode board = send("GET", "/integrations/add-book/board", null);
        assertThat(board.path("input").path("id").asText()).isEqualTo("new-book");
        assertThat(board.path("operation").path("requestBody").path("fields").toString()).contains("\"name\":\"pageCount\"");

        send("PUT", "/integrations/add-book/links", """
                {"request":[{"from":"nb-title","to":"body.title"},{"from":"nb-pages","to":"body.pageCount"}],
                 "response":[{"from":"id","to":"br-id"},{"from":"title","to":"br-title"}]}""");
        assertThat(Files.readString(CONFIG.resolve("services/add-book.json"))).contains("body.pageCount");

        // Mock: FakeRESTApi's POST /Books declares no response schema, so the mock is empty and the drawer says why.
        JsonNode mock = send("POST", "/integrations/add-book/test", Map.of("input", Map.of("title", "T", "pages", 3)));
        assertThat(mock.path("stage").asText()).isEqualTo("done");
        assertThat(mock.path("mock").asBoolean()).isTrue();
        assertThat(mock.path("request").path("method").asText()).isEqualTo("POST");
        assertThat(mock.path("request").path("body").toString()).isEqualTo("{\"title\":\"T\",\"pageCount\":3}");
        assertThat(mock.path("problems").toString()).contains("does not describe the response");

        // A GET operation with a response schema gets a mock built from it.
        send("PUT", "/integrations/add-book/operation", Map.of("contract", "fake-rest-api", "operationId", "GET /api/v1/Books/{id}"));
        send("PUT", "/integrations/add-book/payloads", Map.of("input", "book-ref", "output", "book-ref"));
        send("PUT", "/integrations/add-book/links", """
                {"request":[{"from":"br-id","to":"path.id"}],"response":[{"from":"id","to":"br-id"},{"from":"title","to":"br-title"}]}""");
        JsonNode get = send("POST", "/integrations/add-book/test", Map.of("input", Map.of("bookId", "7")));
        assertThat(get.path("stage").asText()).as(get.toString()).isEqualTo("done");
        assertThat(get.path("request").path("url").asText()).endsWith("/api/v1/Books/7");
        assertThat(get.path("output").toString()).isEqualTo("{\"bookId\":1,\"title\":\"title 1\"}");

        // Back to the POST operation for the real call.
        send("PUT", "/integrations/add-book/operation", Map.of("contract", "fake-rest-api", "operationId", "POST /api/v1/Books"));
        send("PUT", "/integrations/add-book/payloads", Map.of("input", "new-book", "output", "book-ref"));
        send("PUT", "/integrations/add-book/links", """
                {"request":[{"from":"nb-title","to":"body.title"},{"from":"nb-pages","to":"body.pageCount"}],
                 "response":[{"from":"id","to":"br-id"},{"from":"title","to":"br-title"}]}""");

        // Invalid input stops before any request is built.
        JsonNode bad = send("POST", "/integrations/add-book/test", Map.of("input", Map.of("pages", "many")));
        assertThat(bad.path("stage").asText()).isEqualTo("input");
        assertThat(bad.path("problems").toString()).contains("title: required").contains("pages: must be a whole number");

        // Real call, here to the local fake; the API key is used but only shown as ***.
        send("PUT", "/integrations/add-book/settings", Map.of("baseUrl", "http://localhost:" + port + "/fake",
                "auth", Map.of("type", "apiKey", "in", "header", "name", "X-Api-Key")));
        JsonNode real = send("POST", "/integrations/add-book/test", Map.of("input", Map.of("title", "Real", "pages", 9), "real", true));
        assertThat(real.path("stage").asText()).as(real.toString()).isEqualTo("done");
        assertThat(real.path("mock").asBoolean()).isFalse();
        assertThat(real.path("output").toString()).isEqualTo("{\"bookId\":99,\"title\":\"Real\"}");
        assertThat(real.path("request").path("headers").path("X-Api-Key").asText()).isEqualTo("***");
        assertThat(((HttpHeaders) fake.lastRequest.get("headers")).getFirst("X-Api-Key")).isEqualTo("k-123");
        assertThat(real.toString()).doesNotContain("k-123");
    }
}
