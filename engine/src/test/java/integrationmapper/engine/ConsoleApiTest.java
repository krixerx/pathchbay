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

/** The console's API over HTTP, against a real config folder. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleApiTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("console-config");
            Files.createDirectories(CONFIG.resolve("entities"));
            Files.writeString(CONFIG.resolve("entities/book.json"), """
                    {"id":"book","name":"Book","fields":[{"id":"b-title","name":"title","type":"text"}]}""");
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

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api" + path))
                .header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.MAPPER.readTree(r.body());
    }

    @Test
    void newIntegrationPickPayloadsAndSeeUsedBy() throws Exception {
        HttpResponse<String> created = send("POST", "/integrations", "{\"name\":\"Register Person!\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(json(created).path("id").asText()).isEqualTo("register-person");
        assertThat(send("POST", "/integrations", "{\"name\":\"register person\"}").body()).contains("already exists");

        assertThat(send("PUT", "/integrations/register-person/payloads", "{\"input\":\"book\",\"output\":\"book\"}").statusCode())
                .isEqualTo(200);
        assertThat(send("PUT", "/integrations/register-person/payloads", "{\"input\":\"nope\"}").body())
                .contains("Unknown payload definition nope");

        JsonNode list = json(send("GET", "/integrations", null));
        assertThat(list.toString()).contains("\"id\":\"register-person\"").contains("\"status\":\"draft\"");
        assertThat(json(send("GET", "/definitions", null)).toString()).contains("\"usedBy\":[\"register-person\"]");
        assertThat(Files.readString(CONFIG.resolve("services/register-person.json"))).contains("\"input\" : \"book\"");
    }

    @Test
    void saveDefinitionValidatesAndWritesTheFile() throws Exception {
        String bad = """
                {"id":"person","name":"Person","fields":[{"id":"x","name":"a","type":"colour"}]}""";
        HttpResponse<String> r = send("PUT", "/definitions/person", bad);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("unknown type colour");
        assertThat(Files.exists(CONFIG.resolve("entities/person.json"))).isFalse();

        String good = """
                {"id":"person","name":"Person","fields":[{"id":"p-name","name":"name","type":"text","required":true}]}""";
        assertThat(send("PUT", "/definitions/person", good).statusCode()).isEqualTo(200);
        assertThat(Files.readString(CONFIG.resolve("entities/person.json"))).contains("p-name");
        assertThat(json(send("GET", "/definitions/person", null)).path("definition").path("name").asText()).isEqualTo("Person");
    }

    @Test
    void inferFromSample() throws Exception {
        HttpResponse<String> r = send("POST", "/definitions/infer", """
                {"id":"order","name":"Order","sample":{"no":"O1","lines":[{"sku":"S1"}],"note":null}}""");
        JsonNode body = json(r);
        assertThat(body.path("definition").path("fields").toString()).contains("\"name\":\"lines\"").contains("\"type\":\"list\"");
        assertThat(body.path("guesses").toString()).contains("note: the sample value is null");
    }
}
