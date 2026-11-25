package integrationmapper.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A start with a stored version whose literal base URL is not http(s) (harden-admin-surface): that integration is
 * reported broken and gets no route; the others start (R10).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BadBaseUrlStartupTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("bad-base-url");
            Files.createDirectories(CONFIG.resolve("entities"));
            Files.writeString(CONFIG.resolve("entities/book-ref.json"), EngineSkeletonTest.BOOK_REF);
            integration("good-book", "https://api.books.example");
            integration("bad-book", "file:/tmp/patchbay-probe");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A draft and its live version 1.0.0, both from before connections, with a literal base URL. */
    static void integration(String slug, String baseUrl) throws IOException {
        String target = "{\"baseUrl\":\"" + baseUrl + "\",\"method\":\"GET\",\"path\":\"/api/v1/Books/{id}\"}";
        String links = """
                "request":{"links":[{"from":"br-bookId","to":"path.id"}]},
                "response":{"links":[{"from":"id","to":"br-bookId"},{"from":"title","to":"br-title"}]}""";
        Files.createDirectories(CONFIG.resolve("services/" + slug + ".versions"));
        Files.writeString(CONFIG.resolve("services/" + slug + ".json"), """
                {"id":"%s","input":"book-ref","output":"book-ref","target":%s,%s,"activeVersion":"1.0.0"}"""
                .formatted(slug, target, links));
        Files.writeString(CONFIG.resolve("services/" + slug + ".versions/1.0.0.json"), """
                {"version":"1.0.0","service":{"id":"%s","version":"1.0.0","input":"book-ref","output":"book-ref","target":%s,%s},
                 "input":%s,"output":%s}""".formatted(slug, target, links, EngineSkeletonTest.BOOK_REF, EngineSkeletonTest.BOOK_REF));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("mapper.config-dir", CONFIG::toString);
    }

    @Autowired
    IntegrationRegistry registry;

    @Autowired
    org.apache.camel.CamelContext camel;

    @Test
    void onlyTheIntegrationWithTheBadAddressIsBroken() {
        assertThat(registry.activeVersions()).containsEntry("good-book", "1.0.0").doesNotContainKey("bad-book");
        assertThat(registry.brokenReasons()).containsKey("bad-book").doesNotContainKey("good-book");
        assertThat(registry.brokenReasons().get("bad-book")).contains("base URL is refused");
        assertThat(camel.getRoute("int-bad-book")).isNull();
        assertThat(camel.getRoute("int-good-book")).isNotNull();
        assertThat(Path.of("/tmp/patchbay-probe")).doesNotExist();
    }
}
