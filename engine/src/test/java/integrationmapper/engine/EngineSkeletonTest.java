package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

/** End-to-end through the real Camel routes against {@link FakeBooksApi}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mapper.config-dir=target/no-config")
class EngineSkeletonTest {

    static final String NEW_BOOK = """
            {"id":"new-book","fields":[
              {"id":"nb-title","name":"title","type":"text","required":true},
              {"id":"nb-pages","name":"pages","type":"integer","required":true},
              {"id":"nb-published","name":"published","type":"datetime"},
              {"id":"nb-source","name":"source","type":"text","value":"Patchbay"}]}""";
    static final String BOOK_REF = """
            {"id":"book-ref","fields":[
              {"id":"br-bookId","name":"bookId","type":"integer","required":true},
              {"id":"br-title","name":"title","type":"text"},
              {"id":"br-pages","name":"pages","type":"integer"}]}""";

    @Autowired
    IntegrationPublisher publisher;

    @Autowired
    IntegrationRegistry registry;

    @Autowired
    PayloadLibrary library;

    @Autowired
    FakeBooksApi fake;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String base() {
        return "http://localhost:" + port + "/fake";
    }

    static ServiceDefinition.Link link(String from, String to) {
        return new ServiceDefinition.Link(from, to);
    }

    static PayloadDefinition definition(String json) throws Exception {
        return Json.MAPPER.readValue(json, PayloadDefinition.class);
    }

    ServiceDefinition createBook(String version, String baseUrl) {
        return new ServiceDefinition("create-book", version, null, "new-book", "book-ref",
                new ServiceDefinition.Target(baseUrl, "POST", "/api/v1/Books"), null,
                new ServiceDefinition.Mapping(List.of(link("nb-title", "body.title"), link("nb-pages", "body.pageCount"),
                        link("nb-source", "body.excerpt"))),
                new ServiceDefinition.Mapping(List.of(link("id", "br-bookId"), link("title", "br-title"))));
    }

    ServiceDefinition getBook() {
        return new ServiceDefinition("get-book", "1.0.0", null, "book-ref", "book-ref",
                new ServiceDefinition.Target(base(), "GET", "/api/v1/Books/{id}"), null,
                new ServiceDefinition.Mapping(List.of(link("br-bookId", "path.id"))),
                new ServiceDefinition.Mapping(List.of(link("id", "br-bookId"), link("pageCount", "br-pages"))));
    }

    @BeforeEach
    void publish() throws Exception {
        library.put(definition(NEW_BOOK));
        library.put(definition(BOOK_REF));
        publisher.publish(createBook("1.0.0", base()));
        publisher.publish(getBook());
        fake.lastRequest.clear();
    }

    HttpResponse<String> post(String slug, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug))
                .header("Content-Type", contentType)
                .header("X-API-Key", "caller-secret")
                .header("Authorization", "Bearer caller-token")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + pathAndQuery))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode sentBody() throws Exception {
        return Json.MAPPER.readTree((String) fake.lastRequest.get("body"));
    }

    @Test
    void postMapsRequestAndResponseAndStripsCallerHeaders() throws Exception {
        HttpResponse<String> r = post("create-book", "application/json", "{\"title\":\"T\",\"pages\":42,\"ignored\":true}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(r.body())).isEqualTo(Json.MAPPER.readTree("{\"bookId\":99,\"title\":\"T\"}"));
        assertThat(sentBody()).isEqualTo(Json.MAPPER.readTree(
                "{\"title\":\"T\",\"pageCount\":42,\"excerpt\":\"Patchbay\"}"));

        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.containsHeader("X-API-Key")).as("caller key must not reach the vendor").isFalse();
        assertThat(sent.containsHeader("Authorization")).as("caller auth must not reach the vendor").isFalse();
        assertThat(r.headers().firstValue("X-Vendor-Secret")).as("vendor headers must not reach the caller").isEmpty();
    }

    @Test
    void constantCannotBeOverriddenByTheCaller() throws Exception {
        post("create-book", "application/json", "{\"title\":\"T\",\"pages\":1,\"source\":\"caller\"}");
        assertThat(sentBody().path("excerpt").asText()).isEqualTo("Patchbay");
    }

    @Test
    void invalidInputIs400WithEveryProblemAndNoExternalCall() throws Exception {
        HttpResponse<String> r = post("create-book", "application/json", "{\"pages\":\"many\",\"published\":\"yesterday\"}");

        assertThat(r.statusCode()).isEqualTo(400);
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").asText()).isEqualTo("bad-request");
        assertThat(body.path("problems").toString())
                .contains("title: required")
                .contains("pages: must be a whole number")
                .contains("published: must be a date-time");
        assertThat(fake.lastRequest).as("vendor not called").isEmpty();
    }

    @Test
    void getTakesTypedQueryParametersIntoThePath() throws Exception {
        HttpResponse<String> r = get("get-book?bookId=7");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(fake.lastRequest.get("path")).isEqualTo("7");
        JsonNode out = Json.MAPPER.readTree(r.body());
        assertThat(out.path("bookId").isIntegralNumber()).isTrue();
        assertThat(out.path("pages").asInt()).isEqualTo(700);
    }

    @Test
    void badOrMissingQueryParameterIs400() throws Exception {
        HttpResponse<String> notNumber = get("get-book?bookId=seven");
        assertThat(notNumber.statusCode()).isEqualTo(400);
        assertThat(notNumber.body()).contains("bookId: must be a whole number");

        HttpResponse<String> missing = get("get-book");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("bookId: required");
        assertThat(fake.lastRequest).isEmpty();
    }

    @Test
    void nonJsonBodyIs400AndFormBodyIs415() throws Exception {
        assertThat(post("create-book", "application/json", "not json").statusCode()).isEqualTo(400);
        HttpResponse<String> form = post("create-book", "application/x-www-form-urlencoded", "a=b");
        assertThat(form.statusCode()).isEqualTo(415);
        assertThat(form.body()).contains("unsupported-media-type");
        assertThat(fake.lastRequest).isEmpty();
    }

    @Test
    void remoteErrorPassesThrough() throws Exception {
        HttpResponse<String> r = get("get-book?bookId=404");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.headers().firstValue("X-Integration-Error")).hasValue("remote");
        assertThat(r.body()).contains("Not Found");
    }

    @Test
    void unreachableRemoteIs502() throws Exception {
        publisher.publish(createBook("1.0.1", "http://localhost:1"));
        HttpResponse<String> r = post("create-book", "application/json", "{\"title\":\"T\",\"pages\":1}");
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).contains("\"integration\":\"create-book\"");
    }

    @Test
    void republishSwapsVersionAndBrokenVersionChangesNothing() throws Exception {
        publisher.publish(createBook("1.1.0", base()));
        assertThat(registry.activeVersions()).containsEntry("create-book", "1.1.0");

        ServiceDefinition broken = new ServiceDefinition("create-book", "1.2.0", null, "new-book", "book-ref",
                new ServiceDefinition.Target(base(), "POST", "/api/v1/Books"), null,
                new ServiceDefinition.Mapping(List.of(link("no-such-field", "body.title"))),
                new ServiceDefinition.Mapping(List.of()));
        assertThatThrownBy(() -> publisher.publish(broken)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-field");

        assertThat(registry.activeVersions()).containsEntry("create-book", "1.1.0");
        assertThat(post("create-book", "application/json", "{\"title\":\"T\",\"pages\":1}").statusCode()).isEqualTo(200);
    }

    @Test
    void libraryEditDoesNotChangeTheLiveVersionUntilRepublished() throws Exception {
        // Rename "title" to "name" in the shared definition; the live version keeps its own copy (D-9).
        library.put(definition(NEW_BOOK.replace("\"name\":\"title\"", "\"name\":\"name\"")));
        assertThat(post("create-book", "application/json", "{\"title\":\"Old\",\"pages\":1}").statusCode()).isEqualTo(200);
        assertThat(sentBody().path("title").asText()).isEqualTo("Old");

        // After republishing, the same link (by field id) reads the renamed field (D-18).
        publisher.publish(createBook("1.1.0", base()));
        post("create-book", "application/json", "{\"name\":\"New\",\"pages\":1}");
        assertThat(sentBody().path("title").asText()).isEqualTo("New");
    }

    @Test
    void getIntegrationNeedsAFlatInput() throws Exception {
        library.put(definition("""
                {"id":"nested","fields":[{"id":"n-a","name":"a","type":"object","fields":[
                  {"id":"n-b","name":"b","type":"text"}]}]}"""));
        ServiceDefinition def = new ServiceDefinition("nested-get", "1.0.0", null, "nested", "book-ref",
                new ServiceDefinition.Target(base(), "GET", "/api/v1/Books/1"), null,
                new ServiceDefinition.Mapping(List.of()), new ServiceDefinition.Mapping(List.of()));
        assertThatThrownBy(() -> publisher.publish(def)).hasMessageContaining("only have simple fields");
    }
}
