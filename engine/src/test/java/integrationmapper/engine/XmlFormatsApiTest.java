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

/** The board's format switches (D-24): saving them, and the Test drawer showing XML as it is sent and returned. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class XmlFormatsApiTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("xml-config");
            Files.createDirectories(CONFIG.resolve("entities"));
            Files.createDirectories(CONFIG.resolve("services"));
            Files.writeString(CONFIG.resolve("entities/new-book.json"), EngineSkeletonTest.NEW_BOOK);
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

    HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/integrations/xml-book" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void formatsAreSavedCheckedAndUsedByTheTestDrawer() throws Exception {
        Files.writeString(CONFIG.resolve("services/xml-book.json"), """
                {"id":"xml-book","input":"new-book","output":"book-ref",
                 "target":{"baseUrl":"http://localhost:%d/fake","method":"POST","path":"/xml/Books"},
                 "request":{"links":[{"from":"nb-title","to":"body.title"},{"from":"nb-pages","to":"body.pageCount"}]},
                 "response":{"links":[{"from":"id","to":"br-bookId"},{"from":"pageCount","to":"br-pages"}]}}""".formatted(port));

        HttpResponse<String> noRoot = send("PUT", "/formats", "{\"request\":\"xml\"}");
        assertThat(noRoot.statusCode()).isEqualTo(400);
        assertThat(noRoot.body()).contains("request root element");

        HttpResponse<String> saved = send("PUT", "/formats", "{\"request\":\"xml\",\"requestRoot\":\"Book\",\"output\":\"xml\",\"outputRoot\":\"bookRef\"}");
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(saved.body()).path("formats").toString())
                .isEqualTo("{\"request\":\"xml\",\"requestRoot\":\"Book\",\"output\":\"xml\",\"outputRoot\":\"bookRef\"}");

        HttpResponse<String> run = send("POST", "/test", "{\"input\":{\"title\":\"T\",\"pages\":7},\"real\":true}");
        JsonNode result = Json.MAPPER.readTree(run.body());
        assertThat(result.path("stage").asText()).as(run.body()).isEqualTo("done");
        assertThat(result.path("request").path("body").asText()).isEqualTo("<Book><title>T</title><pageCount>7</pageCount></Book>");
        assertThat(result.path("request").path("headers").path("Accept").asText()).isEqualTo("application/xml");
        assertThat(result.path("output").asText()).isEqualTo("<bookRef><bookId>99</bookId><pages>7</pages></bookRef>");

        // Back to JSON: the setting disappears from the draft.
        HttpResponse<String> json = send("PUT", "/formats", "{\"request\":\"json\",\"requestRoot\":\"Book\"}");
        assertThat(Json.MAPPER.readTree(json.body()).has("formats")).isFalse();

        // Files (D-29): binary and multipart are kept, without XML roots; an unknown format is refused.
        HttpResponse<String> binary = send("PUT", "/formats", "{\"request\":\"multipart\",\"requestRoot\":\"Book\",\"output\":\"binary\"}");
        assertThat(Json.MAPPER.readTree(binary.body()).path("formats").toString()).isEqualTo("{\"request\":\"multipart\",\"output\":\"binary\"}");
        HttpResponse<String> pdf = send("PUT", "/formats", "{\"output\":\"pdf\"}");
        assertThat(pdf.statusCode()).isEqualTo(400);
        assertThat(pdf.body()).contains("output format must be json, xml or binary, not pdf");
    }
}
