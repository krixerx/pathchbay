package integrationmapper.engine;

import static integrationmapper.engine.EngineSkeletonTest.definition;
import static integrationmapper.engine.EngineSkeletonTest.link;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

/** XML towards the external system and towards callers, through the real Camel routes (D-24, TODO-T4). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "mapper.config-dir=target/no-config")
class XmlIntegrationTest {

    static final String BOOK_LIST = """
            {"id":"book-list","fields":[
              {"id":"bl-books","name":"books","type":"list","items":{"type":"object","fields":[
                {"id":"bl-bookId","name":"bookId","type":"integer"},
                {"id":"bl-title","name":"title","type":"text"}]}}]}""";
    static final String COUNT = """
            {"id":"count","fields":[{"id":"c-count","name":"count","type":"integer","required":true}]}""";

    @Autowired
    IntegrationPublisher publisher;

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

    ServiceDefinition createBook(ServiceDefinition.Formats formats) {
        return new ServiceDefinition("xml-create", "1.0.0", null, "new-book", "book-ref",
                new ServiceDefinition.Target(base(), "POST", "/xml/Books"), null,
                new ServiceDefinition.Mapping(List.of(link("nb-title", "body.title"), link("nb-pages", "body.pageCount"))),
                new ServiceDefinition.Mapping(List.of(link("id", "br-bookId"), link("title", "br-title"), link("pageCount", "br-pages"))),
                formats);
    }

    @BeforeEach
    void definitions() throws Exception {
        library.put(definition(EngineSkeletonTest.NEW_BOOK));
        library.put(definition(EngineSkeletonTest.BOOK_REF));
        library.put(definition(BOOK_LIST));
        library.put(definition(COUNT));
        fake.lastRequest.clear();
    }

    HttpResponse<String> call(String slug, String method, String query, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug + query))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sendsXmlAndReadsTheXmlAnswerIntoTypedJson() throws Exception {
        publisher.publish(createBook(new ServiceDefinition.Formats("xml", "Book", null, null)));

        HttpResponse<String> r = call("xml-create", "POST", "", "{\"title\":\"T\",\"pages\":42}");

        assertThat(fake.lastRequest.get("body")).isEqualTo("<Book><title>T</title><pageCount>42</pageCount></Book>");
        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Content-Type")).startsWith("application/xml");
        assertThat(sent.getFirst("Accept")).isEqualTo("application/xml");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        // The id is an attribute in the answer; numbers are typed by the Output definition.
        assertThat(r.body()).isEqualTo("{\"bookId\":99,\"title\":\"T\",\"pages\":42}");
    }

    @Test
    void returnsXmlToCallers() throws Exception {
        publisher.publish(createBook(new ServiceDefinition.Formats("xml", "Book", "xml", "bookRef")));

        HttpResponse<String> r = call("xml-create", "POST", "", "{\"title\":\"T\",\"pages\":42}");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/xml"));
        assertThat(r.body()).isEqualTo("<bookRef><bookId>99</bookId><title>T</title><pages>42</pages></bookRef>");
    }

    @Test
    void anXmlListOfOneIsStillAList() throws Exception {
        publisher.publish(new ServiceDefinition("xml-list", "1.0.0", null, "count", "book-list",
                new ServiceDefinition.Target(base(), "GET", "/xml/Books"), null,
                new ServiceDefinition.Mapping(List.of(link("c-count", "query.count"))),
                new ServiceDefinition.Mapping(List.of(link("[]", "bl-books"), link("[].id", "bl-bookId"), link("[].title", "bl-title"))),
                null));

        assertThat(call("xml-list", "GET", "?count=1", null).body())
                .isEqualTo("{\"books\":[{\"bookId\":1,\"title\":\"Book 1\"}]}");
        assertThat(call("xml-list", "GET", "?count=2", null).body())
                .isEqualTo("{\"books\":[{\"bookId\":1,\"title\":\"Book 1\"},{\"bookId\":2,\"title\":\"Book 2\"}]}");
        assertThat(call("xml-list", "GET", "?count=0", null).body()).isEqualTo("{\"books\":[]}");
    }

    @Test
    void anXmlFormatWithoutARootElementDoesNotPublish() {
        assertThatThrownBy(() -> publisher.publish(createBook(new ServiceDefinition.Formats("xml", null, null, null))))
                .hasMessageContaining("request root element");
    }
}
