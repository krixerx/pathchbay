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

/**
 * Header definitions for the external call (D-27, TODO-T3): headers on the connection, sent on every call with a
 * value per environment, secret ones as *** wherever they are shown; and headers added on the board, linked like
 * any contract header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeaderDefinitionsTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("headers-config");
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
        r.add("MAPPER_HEADER_FAKE_REST_API_X_ENV", () -> "test");
    }

    @LocalServerPort
    int port;

    @Autowired
    FakeBooksApi fake;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        String json = body == null ? null : body instanceof String s ? s : Json.MAPPER.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode send(String method, String path, Object body) throws Exception {
        HttpResponse<String> r = call(method, "/admin/api" + path, body);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body());
    }

    HttpHeaders sent() {
        return (HttpHeaders) fake.lastRequest.get("headers");
    }

    static Map<String, Object> header(String name, String value, boolean secret) {
        return Map.of("name", name, "value", value, "secret", secret);
    }

    @Test
    void connectionAndBoardHeadersReachTheExternalSystem() throws Exception {
        send("POST", "/contracts", Map.of("name", "Fake REST API", "content", ContractImporterTest.fixture()));
        send("POST", "/integrations", Map.of("name", "Add book"));
        send("PUT", "/integrations/add-book/payloads", Map.of("input", "new-book", "output", "book-ref"));
        send("PUT", "/integrations/add-book/operation", Map.of("contract", "fake-rest-api", "operationId", "POST /api/v1/Books"));

        // A header the contract doesn't declare, added on the board and linked from the Input.
        JsonNode draft = send("PUT", "/integrations/add-book/headers", Map.of("headers", List.of("X-Correlation-Id")));
        assertThat(draft.path("addedHeaders").toString()).isEqualTo("[\"X-Correlation-Id\"]");
        send("PUT", "/integrations/add-book/links", """
                {"request":[{"from":"nb-title","to":"body.title"},{"from":"nb-pages","to":"headers.X-Correlation-Id"}],
                 "response":[{"from":"id","to":"br-id"},{"from":"title","to":"br-title"}]}""");

        // Connection headers: a plain one, a secret one, and one whose value comes from the environment.
        JsonNode connection = send("PUT", "/connections/fake-rest-api", Map.of("baseUrl", "http://localhost:" + port + "/fake",
                "headers", List.of(header("X-Client-Id", "abc", false), header("X-Tenant-Key", "s3cret", true), header("X-Env", "", false))));
        assertThat(connection.toString()).doesNotContain("s3cret");
        JsonNode tenant = connection.path("headers").get(1);
        assertThat(tenant.path("secret").asBoolean()).isTrue();
        assertThat(tenant.path("saved").asBoolean()).isTrue();
        assertThat(tenant.path("value").isNull()).isTrue();
        assertThat(connection.path("headers").get(2).path("inEnv").asBoolean()).isTrue();
        assertThat(connection.path("headers").get(2).path("envName").asText()).isEqualTo("MAPPER_HEADER_FAKE_REST_API_X_ENV");
        assertThat(Files.readString(CONFIG.resolve("connections/fake-rest-api.json"))).contains("X-Tenant-Key").doesNotContain("s3cret");
        assertThat(Files.readString(CONFIG.resolve("secrets/connections/fake-rest-api.json"))).contains("s3cret");

        // The Test drawer sends them all and shows the secret one as ***.
        JsonNode real = send("POST", "/integrations/add-book/test",
                Map.of("input", Map.of("title", "T", "pages", 9), "real", true));
        assertThat(real.path("stage").asText()).as(real.toString()).isEqualTo("done");
        JsonNode shown = real.path("request").path("headers");
        assertThat(shown.path("X-Client-Id").asText()).isEqualTo("abc");
        assertThat(shown.path("X-Tenant-Key").asText()).isEqualTo("***");
        assertThat(shown.path("X-Env").asText()).isEqualTo("test");
        assertThat(shown.path("X-Correlation-Id").asText()).isEqualTo("9");
        assertThat(sent().getFirst("X-Tenant-Key")).isEqualTo("s3cret");
        assertThat(real.toString()).doesNotContain("s3cret");

        // Live: the same headers; the call log masks the secret one.
        send("POST", "/integrations/add-book/publish", null);
        assertThat(call("POST", "/api/v1/integrations/add-book", "{\"title\":\"Live\",\"pages\":3}").statusCode()).isEqualTo(200);
        assertThat(sent().getFirst("X-Client-Id")).isEqualTo("abc");
        assertThat(sent().getFirst("X-Tenant-Key")).isEqualTo("s3cret");
        assertThat(sent().getFirst("X-Env")).isEqualTo("test");
        assertThat(sent().getFirst("X-Correlation-Id")).isEqualTo("3");
        JsonNode log = send("GET", "/integrations/add-book/calls", null);
        assertThat(log.path("calls").get(0).path("request").path("headers").path("X-Tenant-Key").asText()).isEqualTo("***");
        assertThat(log.toString()).doesNotContain("s3cret");

        // A changed value reaches the live version at once; a secret sent without a value keeps the stored one.
        send("PUT", "/connections/fake-rest-api", Map.of("baseUrl", "http://localhost:" + port + "/fake",
                "headers", List.of(header("X-Client-Id", "def", false), header("X-Tenant-Key", "", true), header("X-Env", "", false))));
        call("POST", "/api/v1/integrations/add-book", "{\"title\":\"Live\",\"pages\":3}");
        assertThat(sent().getFirst("X-Client-Id")).isEqualTo("def");
        assertThat(sent().getFirst("X-Tenant-Key")).isEqualTo("s3cret");

        // A header without any value, a reserved name, or a dotted one is refused and nothing changes.
        assertThat(call("PUT", "/admin/api/connections/fake-rest-api", Map.of("baseUrl", "http://localhost:" + port + "/fake",
                "headers", List.of(header("X-New", "", true)))).body()).contains("MAPPER_HEADER_FAKE_REST_API_X_NEW");
        assertThat(call("PUT", "/admin/api/connections/fake-rest-api", Map.of("baseUrl", "http://localhost:" + port + "/fake",
                "headers", List.of(header("Content-Type", "x", false)))).body()).contains("set by the engine");
        assertThat(call("PUT", "/admin/api/integrations/add-book/headers", Map.of("headers", List.of("X.Trace"))).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/connections/fake-rest-api", null).path("headers")).hasSize(3);

        // Removing an added header removes its link too.
        draft = send("PUT", "/integrations/add-book/headers", Map.of("headers", List.of()));
        assertThat(draft.has("addedHeaders")).isFalse();
        assertThat(draft.path("request").path("links").toString()).contains("body.title").doesNotContain("X-Correlation-Id");

        // Removing the connection headers removes their secret file.
        send("PUT", "/connections/fake-rest-api", Map.of("baseUrl", "http://localhost:" + port + "/fake", "headers", List.of()));
        assertThat(CONFIG.resolve("secrets/connections/fake-rest-api.json")).doesNotExist();
        call("POST", "/api/v1/integrations/add-book", "{\"title\":\"Live\",\"pages\":3}");
        assertThat(sent().containsHeader("X-Client-Id")).isFalse();
    }
}
