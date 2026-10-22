package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractImporterTest {

    @TempDir
    Path dir;

    ContractImporter importer() {
        return new ContractImporter(new ConfigStore(dir.toString()));
    }

    static String fixture() throws Exception {
        try (var in = ContractImporterTest.class.getResourceAsStream("/contracts/fakerestapi.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Contract.Operation op(Contract c, String method, String path) {
        return c.operations().stream().filter(o -> o.method().equals(method) && o.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void fakeRestApiIsNormalizedAndBothFilesAreStored() throws Exception {
        Contract c = importer().importContract("fake-rest-api", "FakeRESTApi", fixture(), null);

        assertThat(c.operations()).hasSizeGreaterThan(20);
        assertThat(c.servers()).containsExactly("/"); // no servers in FakeRESTApi: the parser adds "/", a relative URL, so the base URL must be typed in
        Contract.Operation get = op(c, "GET", "/api/v1/Books/{id}");
        assertThat(get.parameters()).containsExactly(new Contract.Parameter("id", "path", true, "integer"));
        assertThat(get.response().type()).isEqualTo("object");
        assertThat(get.response().fields()).extracting(PayloadDefinition.Field::name)
                .contains("id", "title", "description", "pageCount", "publishDate");
        assertThat(get.response().fields().stream().filter(f -> f.name().equals("publishDate")).findFirst().orElseThrow().type())
                .isEqualTo("datetime");
        assertThat(op(c, "GET", "/api/v1/Books").response().type()).isEqualTo("list");
        assertThat(op(c, "GET", "/api/v1/Books").response().items().fields()).extracting(PayloadDefinition.Field::name).contains("title");
        assertThat(op(c, "POST", "/api/v1/Books").requestBody().fields()).extracting(PayloadDefinition.Field::name).contains("title");

        assertThat(Files.readString(dir.resolve("contracts/fake-rest-api.openapi.json"))).isEqualTo(fixture());
        assertThat(Json.MAPPER.readValue(dir.resolve("contracts/fake-rest-api.json").toFile(), Contract.class).operations())
                .hasSameSizeAs(c.operations());
    }

    @Test
    void swagger2IsConvertedAndServerComesFromHostAndBasePath() throws Exception {
        String swagger2 = """
                {"swagger":"2.0","info":{"title":"Old","version":"1"},"host":"api.example.org","basePath":"/v1","schemes":["https"],
                 "paths":{"/people/{id}":{"get":{"operationId":"getPerson","parameters":[{"name":"id","in":"path","required":true,"type":"string"}],
                   "responses":{"200":{"description":"ok","schema":{"type":"object","properties":{"name":{"type":"string"},"born":{"type":"string","format":"date"}}}}}}}}}""";
        Contract c = importer().importContract("old", "Old", swagger2, null);
        assertThat(c.warnings().get(0)).isEqualTo("Swagger 2.0 file converted to OpenAPI 3");
        assertThat(c.servers()).containsExactly("https://api.example.org/v1");
        Contract.Operation get = c.operations().get(0);
        assertThat(get.id()).isEqualTo("getPerson");
        assertThat(get.response().fields()).extracting(PayloadDefinition.Field::type).containsExactly("text", "date");
        assertThat(Files.exists(dir.resolve("contracts/old.openapi.json"))).isTrue();
    }

    @Test
    void combinedAndRecursiveSchemasBecomeWarningsNotFailures() throws Exception {
        String spec = """
                openapi: 3.0.1
                info: {title: T, version: '1'}
                servers: [{url: '/relative'}, {url: 'https://{env}.example.org', variables: {env: {default: api}}}]
                paths:
                  /things:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              allOf:
                                - {type: object, required: [a], properties: {a: {type: string}}}
                                - {type: object, properties: {b: {type: integer}}}
                      responses:
                        '201':
                          description: created
                          content:
                            application/json:
                              schema:
                                oneOf: [{$ref: '#/components/schemas/Node'}, {type: string}]
                components:
                  schemas:
                    Node:
                      type: object
                      properties:
                        name: {type: string}
                        child: {$ref: '#/components/schemas/Node'}
                """;
        Contract c = importer().importContract("t", "T", spec, null);
        Contract.Operation post = c.operations().get(0);
        assertThat(post.id()).isEqualTo("POST /things");
        assertThat(post.requestBody().fields()).extracting(PayloadDefinition.Field::name).containsExactly("a", "b");
        assertThat(post.requestBody().fields().get(0).required()).isTrue();
        assertThat(post.responseStatus()).isEqualTo("201");
        assertThat(post.response().fields()).extracting(PayloadDefinition.Field::name).contains("name", "child");
        assertThat(c.warnings()).as(c.warnings() + " response=" + post.response()).anyMatch(w -> w.contains("different shapes")).anyMatch(w -> w.contains("refers to itself"));
        assertThat(c.servers()).containsExactly("/relative", "https://api.example.org");
        assertThat(Files.exists(dir.resolve("contracts/t.openapi.yaml"))).isTrue();
    }

    @Test
    void notAContractIsRefused() {
        assertThatThrownBy(() -> importer().importContract("x", "X", "{\"hello\":\"world\"}", null))
                .hasMessageContaining("Not an OpenAPI or Swagger file");
    }

    @Test
    void unreachableUrlSaysUploadTheFileInstead() {
        assertThatThrownBy(() -> importer().fetch("http://localhost:1/swagger.json"))
                .hasMessageContaining("upload the file instead");
    }
}
