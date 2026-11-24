package integrationmapper.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import integrationmapper.engine.config.ConfigStore;
import integrationmapper.engine.config.PackagesTest;
import integrationmapper.engine.format.Json;

/**
 * D-31: packages in {@code mapper.import-dir} are imported at startup, and an integration with no live version here
 * gets the version that was live where its package came from. A bad package or a version that can't go live is
 * skipped; the rest still start. A restart imports again but keeps this environment's live choice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StartupImportTest {

    static final Path CONFIG;
    static final Path IMPORT;

    static {
        try {
            CONFIG = Files.createTempDirectory("mapper-config");
            IMPORT = Files.createTempDirectory("mapper-import");
            Path dev = Files.createTempDirectory("mapper-dev");
            byte[] books = PackagesTest.dev(dev).packages().export(List.of("get-book", "create-book", "create-user"));
            Files.write(IMPORT.resolve("10-books.zip"), books);
            Files.write(IMPORT.resolve("20-secure.zip"), secureBook(PackagesTest.entries(books)));
            Files.writeString(IMPORT.resolve("05-broken.zip"), "not a zip");
            Files.writeString(IMPORT.resolve("readme.txt"), "not a package, ignored");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** get-book again as secure-book with bearer auth, whose secret this environment does not have. */
    static byte[] secureBook(Map<String, String> books) throws IOException {
        Map<String, String> files = new TreeMap<>();
        books.forEach((name, content) -> {
            if (name.startsWith("entities/") || name.startsWith("contracts/")) {
                files.put(name, content);
            }
        });
        ObjectNode draft = (ObjectNode) Json.MAPPER.readTree(books.get("services/get-book.json"));
        draft.put("id", "secure-book");
        draft.putObject("auth").put("type", "bearer");
        files.put("services/secure-book.json", draft.toString());
        ObjectNode version = (ObjectNode) Json.MAPPER.readTree(books.get("services/get-book.versions/1.0.0.json"));
        ObjectNode service = (ObjectNode) version.get("service");
        service.put("id", "secure-book");
        service.putObject("auth").put("type", "bearer");
        files.put("services/secure-book.versions/1.0.0.json", version.toString());
        files.put("manifest.json", """
                {"format":"patchbay-package","formatVersion":1,"exportedAt":"2026-11-24T00:00:00Z",
                 "integrations":[{"id":"secure-book","activeVersion":"1.0.0"}]}""");
        return PackagesTest.zip(files);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("mapper.config-dir", CONFIG::toString);
        r.add("mapper.import-dir", IMPORT::toString);
        r.add("MAPPER_URL_FAKE_REST_API", () -> "http://localhost:1");
    }

    @Autowired
    IntegrationRegistry registry;

    @Autowired
    IntegrationPublisher publisher;

    @Autowired
    ConfigLoader loader;

    @Autowired
    ConfigStore store;

    String activeVersion(String slug) throws IOException {
        return Json.MAPPER.readTree(store.service(slug).toFile()).path("activeVersion").asText(null);
    }

    @Test
    void startupImportsEveryPackageAndServesTheVersionThatWasLive() throws IOException {
        assertThat(registry.activeVersions()).containsEntry("get-book", "1.0.0").containsKey("create-book");
        assertThat(activeVersion("get-book")).isEqualTo("1.0.0");
        assertThat(Files.isRegularFile(CONFIG.resolve("entities/book-details.json"))).isTrue();
        assertThat(Files.isRegularFile(CONFIG.resolve("contracts/fake-rest-api.json"))).isTrue();
        // Never published where it came from: stored, not served.
        assertThat(Files.isRegularFile(store.service("create-user"))).isTrue();
        assertThat(registry.activeVersions()).doesNotContainKey("create-user");
    }

    @Test
    void aVersionThatCannotGoLiveIsListedAsBrokenAndLeftInactive() throws IOException {
        assertThat(registry.activeVersions()).doesNotContainKey("secure-book");
        assertThat(registry.brokenReasons().get("secure-book")).contains("1.0.0").containsIgnoringCase("secret");
        assertThat(activeVersion("secure-book")).isNull();
        assertThat(store.versions("secure-book")).containsExactly("1.0.0");
    }

    @Test
    void aRestartImportsAgainButKeepsThisEnvironmentsLiveVersion() throws Exception {
        assertThat(publisher.publishDraft("create-book")).isEqualTo("1.1.0");

        loader.loadAll();

        assertThat(registry.activeVersions()).containsEntry("create-book", "1.1.0").containsEntry("get-book", "1.0.0");
        assertThat(activeVersion("create-book")).isEqualTo("1.1.0");
        assertThat(store.versions("create-book")).containsExactly("1.0.0", "1.1.0");
    }

    @Test
    void theIgnoredFilesAreLeftAlone() throws IOException {
        assertThat(Files.readString(IMPORT.resolve("readme.txt"), StandardCharsets.UTF_8)).isEqualTo("not a package, ignored");
        assertThat(Files.exists(IMPORT.resolve("10-books.zip"))).isTrue();
    }
}
