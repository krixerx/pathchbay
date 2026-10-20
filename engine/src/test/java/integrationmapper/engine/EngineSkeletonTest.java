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

    @Autowired
    IntegrationPublisher publisher;

    @Autowired
    IntegrationRegistry registry;

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

    ServiceDefinition createBook(String version, String baseUrl) {
        return new ServiceDefinition("create-book", version, new ServiceDefinition.Target(baseUrl, "POST", "/api/v1/Books"),
                new ServiceDefinition.Mapping(List.of(link("title", "body.title"), link("pages", "body.pageCount"))),
                new ServiceDefinition.Mapping(List.of(link("id", "bookId"), link("title", "title"))));
    }

    ServiceDefinition getBook() {
        return new ServiceDefinition("get-book", "1.0.0", new ServiceDefinition.Target(base(), "GET", "/api/v1/Books/{id}"),
                new ServiceDefinition.Mapping(List.of(link("bookId", "path.id"))),
                new ServiceDefinition.Mapping(List.of(link("id", "bookId"), link("pageCount", "pages"))));
    }

    @BeforeEach
    void publish() throws Exception {
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

    @Test
    void postMapsRequestAndResponseAndStripsCallerHeaders() throws Exception {
        HttpResponse<String> r = post("create-book", "application/json", "{\"title\":\"T\",\"pages\":42,\"ignored\":true}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(r.body())).isEqualTo(Json.MAPPER.readTree("{\"bookId\":99,\"title\":\"T\"}"));
        assertThat(Json.MAPPER.readTree((String) fake.lastRequest.get("body")))
                .isEqualTo(Json.MAPPER.readTree("{\"title\":\"T\",\"pageCount\":42}"));

        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.containsHeader("X-API-Key")).as("caller key must not reach the vendor").isFalse();
        assertThat(sent.containsHeader("Authorization")).as("caller auth must not reach the vendor").isFalse();
        assertThat(r.headers().firstValue("X-Vendor-Secret")).as("vendor headers must not reach the caller").isEmpty();
    }

    @Test
    void getTakesQueryParametersIntoThePath() throws Exception {
        HttpResponse<String> r = get("get-book?bookId=7");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(fake.lastRequest.get("path")).isEqualTo("7");
        JsonNode out = Json.MAPPER.readTree(r.body());
        assertThat(out.path("bookId").asInt()).isEqualTo(7);
        assertThat(out.path("pages").asInt()).isEqualTo(700);
    }

    @Test
    void missingPathParameterIs400() throws Exception {
        HttpResponse<String> r = get("get-book");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"error\":\"bad-request\"").contains("'id'");
        assertThat(fake.lastRequest).as("vendor not called").isEmpty();
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
        HttpResponse<String> r = post("create-book", "application/json", "{\"title\":\"T\"}");
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).contains("\"integration\":\"create-book\"");
    }

    @Test
    void republishSwapsVersionAndBrokenVersionChangesNothing() throws Exception {
        publisher.publish(createBook("1.1.0", base()));
        assertThat(registry.activeVersions()).containsEntry("create-book", "1.1.0");

        ServiceDefinition broken = new ServiceDefinition("create-book", "1.2.0",
                new ServiceDefinition.Target(base(), "POST", "/api/v1/Books"),
                new ServiceDefinition.Mapping(List.of(link("title", "nowhere.title"))),
                new ServiceDefinition.Mapping(List.of()));
        assertThatThrownBy(() -> publisher.publish(broken)).isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.activeVersions()).containsEntry("create-book", "1.1.0");
        assertThat(post("create-book", "application/json", "{\"title\":\"T\"}").statusCode()).isEqualTo(200);
    }
}
