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

        // Step 5: publish, publish again, roll back; versions come newest first.
        JsonNode v1 = send("POST", "/integrations/add-book/publish", null);
        assertThat(v1.path("activeVersion").asText()).isEqualTo("1.0.0");
        send("POST", "/integrations/add-book/publish", null);
        JsonNode rolledBack = send("POST", "/integrations/add-book/versions/1.0.0/activate", null);
        assertThat(rolledBack.path("activeVersion").asText()).isEqualTo("1.0.0");
        assertThat(rolledBack.path("versions").findValuesAsText("version")).containsExactly("1.1.0", "1.0.0");
        assertThat(rolledBack.path("versions").get(0).path("publishedAt").asText()).isNotEmpty();

        // R8: a broken link makes publish impossible, also when the console is bypassed.
        send("PUT", "/integrations/add-book/links", """
                {"request":[{"from":"nb-gone","to":"body.title"}],"response":[]}""");
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/admin/api/integrations/add-book/publish"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("nb-gone");
        assertThat(send("GET", "/integrations/add-book/versions", null).path("versions")).hasSize(2);

        // D-22: live calls are kept, newest first, with the four parts and the API key as ***.
        for (String body : List.of("{\"title\":\"Live\",\"pages\":3}", "{\"pages\":\"many\"}")) {
            http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/add-book"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        JsonNode log = send("GET", "/integrations/add-book/calls", null);
        assertThat(log.path("enabled").asBoolean()).isTrue();
        JsonNode badCall = log.path("calls").get(0);
        assertThat(badCall.path("status").asInt()).isEqualTo(400);
        assertThat(badCall.path("stage").asText()).isEqualTo("input");
        assertThat(badCall.path("problems").toString()).contains("title: required");
        JsonNode okCall = log.path("calls").get(1);
        assertThat(okCall.path("stage").asText()).as(okCall.toString()).isEqualTo("done");
        assertThat(okCall.path("version").asText()).isEqualTo("1.0.0");
        assertThat(okCall.path("input").toString()).isEqualTo("{\"title\":\"Live\",\"pages\":3}");
        assertThat(okCall.path("request").path("body").toString()).isEqualTo("{\"title\":\"Live\",\"pageCount\":3}");
        assertThat(okCall.path("request").path("headers").path("X-Api-Key").asText()).isEqualTo("***");
        assertThat(okCall.path("response").path("status").asInt()).isEqualTo(200);
        assertThat(okCall.path("output").toString()).isEqualTo("{\"bookId\":99,\"title\":\"Live\"}");
        assertThat(log.toString()).doesNotContain("k-123");

        // D-23: the live version is described as OpenAPI, and that document imports like any external system's.
        HttpResponse<String> doc = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/openapi.json")).build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode post = Json.MAPPER.readTree(doc.body()).path("paths").path("/api/v1/integrations/add-book").path("post");
        assertThat(post.path("description").asText()).startsWith("Live version 1.0.0.");
        JsonNode in = post.path("requestBody").path("content").path("application/json").path("schema");
        assertThat(in.path("properties").path("pages").path("type").asText()).isEqualTo("integer");
        assertThat(in.path("required").toString()).isEqualTo("[\"title\"]");
        assertThat(post.path("responses").path("200").path("content").path("application/json").path("schema")
                .path("properties").fieldNames()).toIterable().containsExactly("bookId", "title");
        assertThat(status("GET", "/api/openapi/add-book.json")).isEqualTo(200);
        assertThat(status("GET", "/api/openapi/nothing-here.json")).isEqualTo(404);
        JsonNode self = send("POST", "/contracts", Map.of("name", "Mapper itself", "url", "http://localhost:" + port + "/api/openapi.json"));
        assertThat(self.path("operations")).hasSize(1);
        assertThat(self.path("operations").get(0).path("id").asText()).isEqualTo("add-book");
        assertThat(self.path("operations").get(0).path("method").asText()).isEqualTo("POST");
        assertThat(self.path("operations").get(0).path("path").asText()).isEqualTo("/api/v1/integrations/add-book");
        assertThat(self.path("servers").get(0).asText()).isEqualTo("http://localhost:" + port);
        assertThat(status("DELETE", "/admin/api/contracts/" + self.path("id").asText())).isEqualTo(204);

        // Configuration: a contract in use can't be deleted; deleting the integration takes its endpoint down.
        assertThat(send("GET", "/contracts", null).get(0).path("usedBy").toString()).isEqualTo("[\"add-book\"]");
        assertThat(status("DELETE", "/admin/api/contracts/fake-rest-api")).isEqualTo(400);
        assertThat(status("POST", "/api/v1/integrations/add-book")).isNotEqualTo(404);
        assertThat(status("DELETE", "/admin/api/integrations/add-book")).isEqualTo(204);
        assertThat(status("POST", "/api/v1/integrations/add-book")).isEqualTo(404);
        assertThat(CONFIG.resolve("services/add-book.json")).doesNotExist();
        assertThat(CONFIG.resolve("services/add-book.versions")).doesNotExist();
        assertThat(CONFIG.resolve("secrets/add-book.json")).doesNotExist();
        assertThat(status("DELETE", "/admin/api/contracts/fake-rest-api")).isEqualTo(204);
        assertThat(send("GET", "/contracts", null)).isEmpty();
    }

    int status(String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("POST".equals(method) ? "{}" : ""))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
