package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * Export and import between environments (D-28, FR-060, J3): two config folders stand for DEV and TEST. Packages
 * carry drafts, versions, payload definitions and Swagger configurations, never connections or secrets; an import
 * checks everything before it writes, and stores without making anything live.
 */
class PackagesTest {

    record Env(ConfigStore store, PayloadLibrary library, IntegrationRegistry registry, Packages packages) {
    }

    static Env env(Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir.toString());
        PayloadLibrary library = new PayloadLibrary();
        Path entities = dir.resolve("entities");
        if (Files.isDirectory(entities)) {
            try (Stream<Path> s = Files.list(entities)) {
                for (Path p : s.toList()) {
                    library.put(Json.MAPPER.readValue(p.toFile(), PayloadDefinition.class));
                }
            }
        }
        MockEnvironment vars = new MockEnvironment();
        IntegrationRegistry registry = new IntegrationRegistry();
        return new Env(store, library, registry,
                new Packages(store, library, registry, new Connections(vars, store), new SecretResolver(vars, store)));
    }

    /** DEV: the repository's example configuration, plus a Swagger configuration that get-book came from. */
    static Env dev(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("config"))) {
            for (Path p : s.toList()) {
                Path target = dir.resolve(Path.of("config").relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        Files.createDirectories(dir.resolve("contracts"));
        Files.writeString(dir.resolve("contracts/fake-rest-api.json"), """
                {"id":"fake-rest-api","name":"Fake REST API","sourceFile":"fake-rest-api.openapi.json","operations":[]}""");
        Files.writeString(dir.resolve("contracts/fake-rest-api.openapi.json"), "{\"openapi\":\"3.0.3\"}");
        ObjectNode draft = (ObjectNode) Json.MAPPER.readTree(dir.resolve("services/get-book.json").toFile());
        draft.put("contract", "fake-rest-api");
        Files.writeString(dir.resolve("services/get-book.json"), draft.toString());
        Files.createDirectories(dir.resolve("secrets"));
        Files.writeString(dir.resolve("secrets/create-user.json"), "{\"value\":\"dev-token\"}");
        return env(dir);
    }

    static Map<String, String> entries(byte[] zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(f.getKey()));
                zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void exportCarriesWhatTheBoardNeedsAndNothingOfTheEnvironment(@TempDir Path dir) throws IOException {
        Env dev = dev(dir);
        Map<String, String> files = entries(dev.packages().export(List.of("get-book", "create-user")));

        assertThat(files.keySet()).containsExactly("contracts/fake-rest-api.json", "contracts/fake-rest-api.openapi.json",
                "entities/book-details.json", "entities/book-ref.json", "entities/new-user.json", "entities/user-ref.json",
                "manifest.json", "services/create-user.json", "services/get-book.json", "services/get-book.versions/1.0.0.json");
        JsonNode manifest = Json.MAPPER.readTree(files.get("manifest.json"));
        assertThat(manifest.path("format").asText()).isEqualTo("patchbay-package");
        assertThat(manifest.path("integrations").get(0).path("id").asText()).isEqualTo("get-book");
        assertThat(manifest.path("integrations").get(0).path("activeVersion").asText()).isEqualTo("1.0.0");
        assertThat(files.get("services/get-book.json")).doesNotContain("activeVersion");
        assertThat(String.join("", files.values())).doesNotContain("dev-token");
        assertThat(files.get("services/get-book.json")).doesNotContain("baseUrl");

        assertThatThrownBy(() -> dev.packages().export(List.of("nothing-here"))).hasMessageContaining("No integration nothing-here");
    }

    @Test
    void importStoresVersionsAndSaysWhatThisEnvironmentStillNeeds(@TempDir Path devDir, @TempDir Path testDir) throws IOException {
        byte[] pkg = dev(devDir).packages().export(List.of("get-book", "create-user"));
        Env test = env(testDir);

        Packages.ImportReport report = test.packages().importZip(pkg);

        assertThat(report.definitions()).containsExactly("book-details", "book-ref", "new-user", "user-ref");
        assertThat(report.contracts()).containsExactly("fake-rest-api");
        Packages.ImportedIntegration getBook = report.integrations().get(0);
        assertThat(getBook.id()).isEqualTo("get-book");
        assertThat(getBook.versionsAdded()).containsExactly("1.0.0");
        assertThat(getBook.exportedActiveVersion()).isEqualTo("1.0.0");
        assertThat(getBook.liveVersion()).isNull();
        assertThat(getBook.needs()).singleElement().asString().contains("MAPPER_URL_FAKE_REST_API");
        // The example version was published before connections, with DEV's address in it; the report says so.
        assertThat(getBook.warnings()).singleElement().asString().contains("Version 1.0.0 was published before connections");
        Packages.ImportedIntegration createUser = report.integrations().get(1);
        assertThat(createUser.needs()).anySatisfy(n -> assertThat(n).contains("MAPPER_SECRET_CREATE_USER"))
                .anySatisfy(n -> assertThat(n).contains("MAPPER_URL_GOREST"));

        // Stored, not live: the draft has no active version here, the library knows the definitions.
        assertThat(Json.MAPPER.readTree(testDir.resolve("services/get-book.json").toFile()).has("activeVersion")).isFalse();
        assertThat(testDir.resolve("services/get-book.versions/1.0.0.json")).exists();
        assertThat(testDir.resolve("contracts/fake-rest-api.openapi.json")).exists();
        assertThat(test.library().get("book-details").id()).isEqualTo("book-details");
        assertThat(testDir.resolve("connections")).doesNotExist();
        assertThat(testDir.resolve("secrets")).doesNotExist();

        // Importing the same package again changes nothing and says the versions are already here.
        Files.writeString(testDir.resolve("services/get-book.json"),
                Files.readString(testDir.resolve("services/get-book.json")).replaceFirst("\\{", "{\"activeVersion\":\"1.0.0\","));
        Packages.ImportedIntegration again = test.packages().importZip(pkg).integrations().get(0);
        assertThat(again.versionsAdded()).isEmpty();
        assertThat(again.versionsAlreadyHere()).containsExactly("1.0.0");
        assertThat(Json.MAPPER.readTree(testDir.resolve("services/get-book.json").toFile()).path("activeVersion").asText())
                .as("the live version here stays as it was").isEqualTo("1.0.0");
    }

    @Test
    void aChangedVersionOrAFileOutsideThePackageLayoutRefusesTheWholeImport(@TempDir Path devDir, @TempDir Path testDir)
            throws IOException {
        Map<String, String> files = entries(dev(devDir).packages().export(List.of("get-book")));
        Env test = env(testDir);
        test.packages().importZip(zip(files));

        // Same version number, other content: published versions never change.
        files.put("services/get-book.versions/1.0.0.json", files.get("services/get-book.versions/1.0.0.json").replace("/api/v1/Books/{id}", "/other/{id}"));
        files.put("entities/brand-new.json", "{\"id\":\"brand-new\",\"name\":\"New\",\"fields\":[]}");
        files.put("../escape.json", "{}");
        assertThatThrownBy(() -> test.packages().importZip(zip(files)))
                .isInstanceOfSatisfying(Packages.PackageException.class, e -> assertThat(e.problems)
                        .anySatisfy(p -> assertThat(p).contains("already has a different version 1.0.0"))
                        .anySatisfy(p -> assertThat(p).startsWith("../escape.json: not a file a package holds")));
        assertThat(testDir.resolve("entities/brand-new.json")).as("nothing is written when anything is wrong").doesNotExist();
        assertThat(testDir.getParent().resolve("escape.json")).doesNotExist();

        assertThatThrownBy(() -> test.packages().importZip("not a zip".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Not a Patchbay package");
        assertThatThrownBy(() -> test.packages().importZip(zip(Map.of("manifest.json", "{\"format\":\"other\"}"))))
                .hasMessageContaining("Not a Patchbay package this engine can read");
    }
}
