package integrationmapper.engine.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import integrationmapper.engine.config.ConfigStore;
import integrationmapper.engine.format.Json;
import integrationmapper.engine.model.Contract;
import integrationmapper.engine.model.OpenApiContract;
import integrationmapper.engine.model.PayloadDefinition;
import integrationmapper.engine.model.RestOperation;

public class ContractImporterTest {

    @TempDir
    Path dir;

    ContractImporter importer() {
        return new ContractImporter(new ConfigStore(dir.toString()));
    }

    public static String fixture() throws Exception {
        try (var in = ContractImporterTest.class.getResourceAsStream("/contracts/fakerestapi.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static RestOperation op(OpenApiContract c, String method, String path) {
        return c.operations().stream().filter(o -> o.method().equals(method) && o.path().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void fakeRestApiIsNormalizedAndBothFilesAreStored() throws Exception {
        OpenApiContract c = (OpenApiContract) importer().importContract("fake-rest-api", "FakeRESTApi", fixture(), null);

        assertThat(c.operations()).hasSizeGreaterThan(20);
        assertThat(c.servers()).containsExactly("/"); // no servers in FakeRESTApi: the parser adds "/", a relative URL, so the base URL must be typed in
        RestOperation get = op(c, "GET", "/api/v1/Books/{id}");
        assertThat(get.parameters()).containsExactly(new RestOperation.Parameter("id", "path", true, "integer"));
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
        OpenApiContract c = (OpenApiContract) importer().importContract("old", "Old", swagger2, null);
        assertThat(c.warnings().get(0)).isEqualTo("Swagger 2.0 file converted to OpenAPI 3");
        assertThat(c.servers()).containsExactly("https://api.example.org/v1");
        RestOperation get = c.operations().get(0);
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
        OpenApiContract c = (OpenApiContract) importer().importContract("t", "T", spec, null);
        RestOperation post = c.operations().get(0);
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
                .hasMessageContaining("OpenAPI 3, Swagger 2.0 or WSDL 1.1"); // D-30: names every accepted contract type
    }

    /** A $ref to a local file is refused before parsing: Swagger 2.0's converter read it from the working folder. */
    @Test
    void aReferenceToALocalFileIsRefusedAndNotRead() throws Exception {
        Path leak = Path.of("leak-ref-probe.json");
        Files.writeString(leak, "{\"type\":\"object\",\"properties\":{\"secretKey\":{\"type\":\"string\"}}}");
        try {
            String swagger2 = """
                    swagger: '2.0'
                    info: {title: t, version: '1'}
                    paths:
                      /a:
                        get:
                          produces: [application/json]
                          responses:
                            '200': {description: ok, schema: {$ref: 'leak-ref-probe.json'}}
                    """;
            String oas3 = """
                    openapi: 3.0.1
                    info: {title: t, version: '1'}
                    paths:
                      /a:
                        get:
                          responses:
                            '200':
                              description: ok
                              content: {application/json: {schema: {$ref: '../config/connections.json#/x'}}}
                    """;
            assertThatThrownBy(() -> importer().importContract("old", "Old", swagger2, null))
                    .hasMessageContaining("leak-ref-probe.json").hasMessageNotContaining("secretKey");
            assertThatThrownBy(() -> importer().importContract("new", "New", oas3, null))
                    .hasMessageContaining("../config/connections.json#/x");
            assertThat(Files.exists(dir.resolve("contracts/old.json"))).isFalse();
        } finally {
            Files.deleteIfExists(leak);
        }
    }

    /** A URL import may refer to its own origin only; anything else is refused before any request is made. */
    @Test
    void aUrlImportMayReferOnlyToItsOwnOrigin() {
        String text = """
                {"openapi":"3.0.1","paths":{"/a":{"get":{"responses":{"200":{"description":"ok","content":{"application/json":{"schema":
                  {"properties":{
                    "same":{"$ref":"http://api.example.org:80/schemas.json#/A"},
                    "meta":{"$ref":"http://169.254.169.254/latest/meta-data"},
                    "relative":{"$ref":"schemas.json#/B"},
                    "inside":{"$ref":"#/components/schemas/C"}}}}}}}}}}}""";
        assertThat(OpenApiImporter.refusedRefs(text, "http://api.example.org/openapi.json"))
                .containsExactly("http://169.254.169.254/latest/meta-data", "schemas.json#/B");
        assertThat(OpenApiImporter.refusedRefs(text, null))
                .containsExactly("http://169.254.169.254/latest/meta-data", "http://api.example.org:80/schemas.json#/A", "schemas.json#/B");
    }

    /** D-29: format binary is a file (blob) in multipart parts, raw bodies and raw answers; format byte (base64) stays text. */
    @Test
    void binaryBecomesABlobAndBase64StaysText() throws Exception {
        String docs = """
                openapi: 3.0.3
                info: { title: Docs, version: "1" }
                paths:
                  /documents:
                    post:
                      operationId: upload
                      requestBody:
                        content:
                          multipart/form-data:
                            schema:
                              type: object
                              properties:
                                title: { type: string }
                                file: { type: string, format: binary }
                      responses: { "201": { description: ok } }
                  /documents/base64:
                    post:
                      operationId: uploadBase64
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                contentBase64: { type: string, format: byte }
                      responses: { "201": { description: ok } }
                  /documents/{id}/content:
                    parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                    put:
                      operationId: replace
                      requestBody:
                        content:
                          application/pdf:
                            schema: { type: string, format: binary }
                      responses: { "200": { description: ok } }
                    get:
                      operationId: download
                      responses:
                        "200":
                          description: the file
                          content:
                            application/pdf:
                              schema: { type: string, format: binary }
                """;
        OpenApiContract c = (OpenApiContract) importer().importContract("docs", "Docs", docs, null);

        assertThat(op(c, "POST", "/documents").requestBody().fields())
                .extracting(PayloadDefinition.Field::name, PayloadDefinition.Field::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("title", "text"), org.assertj.core.groups.Tuple.tuple("file", "blob"));
        PayloadDefinition.Field base64 = op(c, "POST", "/documents/base64").requestBody().fields().getFirst();
        assertThat(base64.type()).isEqualTo("text");
        assertThat(base64.format()).isEqualTo("byte");
        assertThat(op(c, "PUT", "/documents/{id}/content").requestBody().type()).isEqualTo("blob");
        assertThat(op(c, "GET", "/documents/{id}/content").response().type()).isEqualTo("blob");
        assertThat(c.warnings()).isEmpty();
    }

    @Test
    void unreachableUrlSaysUploadTheFileInstead() {
        assertThatThrownBy(() -> importer().fetch("http://localhost:1/swagger.json"))
                .hasMessageContaining("upload the file instead");
    }

    /** A URL that answers without end (chunked, no length) is cut off at the contract limit, not read whole. */
    @Test
    void aContractUrlThatAnswersTooMuchIsRefused() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();
        server.createContext("/big.yaml", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked
            byte[] block = new byte[64 * 1024];
            java.util.Arrays.fill(block, (byte) 'a');
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 1024; i++) { // up to 64 MB
                    out.write(block);
                    sent.addAndGet(block.length);
                }
            } catch (java.io.IOException e) {
                // the engine stopped reading
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> importer().fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/big.yaml"))
                    .hasMessageContaining("more than 20 MB");
        } finally {
            server.stop(0);
        }
        assertThat(sent.get()).as("bytes sent before the engine stopped reading").isLessThan(64L * 1024 * 1024);
    }

    /** The schemas one WSDL import may fetch are limited like a ZIP's files. */
    @Test
    void aWsdlCanFetchOnlySoManySchemas() {
        var fetch = ContractImporter.limited(url -> "<xs:schema/>");
        for (int i = 0; i < ContractImporter.MAX_ZIP_ENTRIES; i++) {
            fetch.apply("http://x/" + i + ".xsd");
        }
        assertThatThrownBy(() -> fetch.apply("http://x/one-more.xsd")).hasMessageContaining("more than 500 schema files");
    }
}
