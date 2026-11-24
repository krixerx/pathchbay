package integrationmapper.engine.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import integrationmapper.engine.config.Packages;
import integrationmapper.engine.config.PackagesTest;
import integrationmapper.engine.contract.WsdlImporterTest;
import integrationmapper.engine.format.Json;
import integrationmapper.engine.model.Contract;
import integrationmapper.engine.model.PayloadDefinition;
import integrationmapper.engine.model.VersionSnapshot;
import integrationmapper.engine.model.WsdlContract;
import integrationmapper.engine.runtime.CompiledIntegration;
import integrationmapper.engine.testsupport.FakeSoapApi;

/**
 * A WSDL contract from import to a live SOAP integration over the admin API, and in a package (D-30, specs
 * wsdl-contract-import and soap-outbound-call).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SoapContractApiTest {

    static final Path CONFIG;

    static {
        try {
            CONFIG = Files.createTempDirectory("soap-config");
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

    @org.springframework.beans.factory.annotation.Autowired
    FakeSoapApi fake;

    @TempDir
    Path elsewhere;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> send(String method, String path, Object body) throws Exception {
        String json = body == null ? null : body instanceof String s ? s : Json.MAPPER.writeValueAsString(body);
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<byte[]> sendForBytes(String path, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    String wsdlUrl() {
        return "http://localhost:" + port + "/fake-soap/ws/person?wsdl";
    }

    @Test
    void importByUrlFetchesTheSchemaOnTheSameHostAndStoresBoth() throws Exception {
        HttpResponse<String> r = send("POST", "/admin/api/contracts", Map.of("name", "Url registry", "url", wsdlUrl()));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        WsdlContract c = (WsdlContract) Json.MAPPER.readValue(r.body(), Contract.class);
        assertThat(c.sourceUrl()).isEqualTo(wsdlUrl());
        assertThat(c.warnings()).noneMatch(w -> w.contains("xsd=1"));
        assertThat(c.files()).containsValues(wsdlUrl(), wsdlUrl().replace("?wsdl", "?xsd=1"));
        assertThat(Files.list(CONFIG.resolve("contracts/url-registry.wsdl")).map(p -> p.getFileName().toString()))
                .containsExactlyInAnyOrder("person_wsdl.wsdl", "person_xsd=1.xsd");
        assertThat(c.sourceFile()).isEqualTo("url-registry.wsdl/person_wsdl.wsdl");
        PayloadDefinition.Field person = WsdlImporterTest.field(WsdlImporterTest.op(c, "PersonSoap/GetPerson").response(), "person");
        assertThat(WsdlImporterTest.field(person, "address").children()).isNotEmpty();
    }

    @Test
    void aSchemaUrlThatRedirectsIsNotFollowed() throws Exception {
        String url = "http://localhost:" + port + "/fake-soap/ws/moved?wsdl";
        HttpResponse<String> r = send("POST", "/admin/api/contracts", Map.of("name", "Moved registry", "url", url));

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        WsdlContract c = (WsdlContract) Json.MAPPER.readValue(r.body(), Contract.class);
        assertThat(c.warnings()).anyMatch(w -> w.contains("xsd=1") && w.contains("redirect"));
        assertThat(c.files()).containsOnlyKeys("moved_wsdl.wsdl");
    }

    @Test
    void importAsTextAsZipAndDelete() throws Exception {
        HttpResponse<String> text = send("POST", "/admin/api/contracts",
                Map.of("name", "Text registry", "content", WsdlImporterTest.resource("person/person.wsdl")));
        assertThat(text.statusCode()).as(text.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(text.body()).path("warnings").toString()).contains("schemas/common.xsd");

        String zip = Base64.getEncoder().encodeToString(WsdlImporterTest.personZip());
        HttpResponse<String> zipped = send("PUT", "/admin/api/contracts/text-registry", Map.of("zip", zip));
        assertThat(zipped.statusCode()).as(zipped.body()).isEqualTo(200);
        assertThat(Files.exists(CONFIG.resolve("contracts/text-registry.wsdl/schemas/common.xsd"))).isTrue();
        assertThat(Files.exists(CONFIG.resolve("contracts/text-registry.wsdl/service.wsdl"))).isFalse();

        HttpResponse<String> renamed = send("PUT", "/admin/api/contracts/text-registry", Map.of("name", "Renamed registry"));
        assertThat(Json.MAPPER.readTree(renamed.body()).path("sourceKind").asText()).isEqualTo("wsdl");

        assertThat(send("POST", "/admin/api/contracts", Map.of("name", "Bad", "zip", "%%%")).body()).contains("not valid base64");

        assertThat(send("DELETE", "/admin/api/contracts/text-registry", null).statusCode()).isEqualTo(204);
        assertThat(Files.exists(CONFIG.resolve("contracts/text-registry.wsdl"))).isFalse();
        assertThat(Files.exists(CONFIG.resolve("contracts/text-registry.json"))).isFalse();
    }

    @Test
    void choosePublishAndCallASoapOperationThenCarryItInAPackage() throws Exception {
        assertThat(send("POST", "/admin/api/contracts", Map.of("name", "Person registry", "url", wsdlUrl())).statusCode()).isEqualTo(200);
        send("POST", "/admin/api/integrations", Map.of("name", "Find person"));

        // An RPC operation is listed but can't be chosen.
        HttpResponse<String> rpc = send("PUT", "/admin/api/integrations/find-person/operation",
                Map.of("contract", "person-registry", "operationId", "PersonRpc/GetPerson"));
        assertThat(rpc.statusCode()).isEqualTo(400);
        assertThat(rpc.body()).contains("RPC style");

        HttpResponse<String> chosen = send("PUT", "/admin/api/integrations/find-person/operation",
                Map.of("contract", "person-registry", "operationId", "PersonSoap/GetPerson"));
        assertThat(chosen.statusCode()).as(chosen.body()).isEqualTo(200);
        JsonNode draft = Json.MAPPER.readTree(chosen.body());
        assertThat(draft.path("target").path("method").asText()).isEqualTo("POST");
        assertThat(draft.path("target").path("soap").path("action").asText()).isEqualTo("urn:GetPerson");
        assertThat(draft.path("target").path("soap").path("body").path("name").asText()).isEqualTo("GetPerson");
        assertThat(draft.path("formats").path("request").asText()).isEqualTo("soap");
        assertThat(draft.path("request").path("links").toString()).contains("body.personCode");
        // The connection's base URL comes from the port address.
        JsonNode connection = Json.MAPPER.readTree(send("GET", "/admin/api/connections/person-registry", null).body());
        assertThat(connection.path("baseUrl").asText()).isEqualTo("http://localhost:" + port + "/fake-soap/person");

        // The request format of a SOAP operation stays soap.
        JsonNode formats = Json.MAPPER.readTree(send("PUT", "/admin/api/integrations/find-person/formats",
                Map.of("request", "json", "output", "xml", "outputRoot", "card")).body());
        assertThat(formats.path("formats").path("request").asText()).isEqualTo("soap");
        assertThat(formats.path("formats").path("output").asText()).isEqualTo("xml");
        send("PUT", "/admin/api/integrations/find-person/formats", Map.of("request", "json", "output", "json"));

        HttpResponse<String> published = send("POST", "/admin/integrations/find-person/publish", null);
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        HttpResponse<String> r = send("POST", "/api/v1/integrations/find-person", Map.of("personCode", "38001010000"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(r.body()).path("person").path("lastName").asText()).isEqualTo("Maasikas-38001010000");

        // The Test drawer: a mock envelope built from the contract, and a real call sent like a live one.
        JsonNode mock = Json.MAPPER.readTree(send("POST", "/admin/api/integrations/find-person/test",
                Map.of("input", Map.of("personCode", "7"), "real", false)).body());
        assertThat(mock.path("stage").asText()).as(mock.toString()).isEqualTo("done");
        assertThat(mock.path("request").path("body").asText()).contains("<ns1:personCode>7</ns1:personCode>");
        assertThat(mock.path("request").path("headers").path("SOAPAction").asText()).isEqualTo("\"urn:GetPerson\"");
        assertThat(mock.path("response").path("body").asText()).contains("GetPersonResponse");
        assertThat(mock.path("output").path("person").path("firstName").asText()).isEqualTo("firstName 1");
        fake.lastRequest.clear();
        JsonNode real = Json.MAPPER.readTree(send("POST", "/admin/api/integrations/find-person/test",
                Map.of("input", Map.of("personCode", "8"), "real", true)).body());
        assertThat(real.path("stage").asText()).as(real.toString()).isEqualTo("done");
        assertThat(real.path("output").path("person").path("lastName").asText()).isEqualTo("Maasikas-8");
        org.springframework.http.HttpHeaders sent = (org.springframework.http.HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.get("Content-Type")).containsExactly("text/xml; charset=utf-8");
        assertThat(sent.getFirst("SOAPAction")).isEqualTo("\"urn:GetPerson\"");

        // The version file carries the SOAP details; a re-import changes the draft, not the live version.
        Path version = CONFIG.resolve("services/find-person.versions/1.0.0.json");
        JsonNode v = Json.MAPPER.readTree(version.toFile());
        assertThat(v.path("service").path("target").path("soap").path("body").path("ns").asText()).isEqualTo("urn:example:person");
        String before = Files.readString(version);
        HttpResponse<String> v2 = send("PUT", "/admin/api/contracts/person-registry",
                Map.of("content", WsdlImporterTest.resource("person/person.wsdl").replace("urn:example:person", "urn:example:person:v2")));
        assertThat(v2.body()).contains("find-person takes the new SOAP details");
        assertThat(Files.readString(version)).isEqualTo(before);
        assertThat(Json.MAPPER.readTree(CONFIG.resolve("services/find-person.json").toFile()).path("target").path("soap").path("body")
                .path("ns").asText()).isEqualTo("urn:example:person:v2");
        assertThat(send("POST", "/api/v1/integrations/find-person", Map.of("personCode", "1")).body()).contains("Maasikas-1");
        assertThat((String) fake.lastRequest.get("body")).contains("xmlns:ns1=\"urn:example:person\"").doesNotContain("person:v2");
        send("PUT", "/admin/api/contracts/person-registry", Map.of("url", wsdlUrl()));
        assertThat(Json.MAPPER.readTree(CONFIG.resolve("services/find-person.json").toFile()).path("target").path("soap").path("body")
                .path("ns").asText()).isEqualTo("urn:example:person");

        // A package carries the WSDL folder; another environment imports it without fetching anything.
        HttpResponse<byte[]> exported = sendForBytes("/admin/api/export", Map.of("integrations", List.of("find-person")));
        assertThat(exported.statusCode()).isEqualTo(200);
        Map<String, String> entries = PackagesTest.entries(exported.body());
        assertThat(entries).containsKeys("contracts/person-registry.json", "contracts/person-registry.wsdl/person_wsdl.wsdl",
                "contracts/person-registry.wsdl/person_xsd=1.xsd", "services/find-person.versions/1.0.0.json");

        PackagesTest.Env test = PackagesTest.env(elsewhere);
        Packages.ImportReport report = test.packages().importZip(exported.body());
        assertThat(report.integrations()).extracting(Packages.ImportedIntegration::id).containsExactly("find-person");
        assertThat(Files.exists(elsewhere.resolve("contracts/person-registry.wsdl/person_xsd=1.xsd"))).isTrue();
        VersionSnapshot s = Json.MAPPER.readValue(elsewhere.resolve("services/find-person.versions/1.0.0.json").toFile(), VersionSnapshot.class);
        CompiledIntegration.compile(s.service(), s.input(), s.output(), null, "http://test.example/ws");
        assertThat(s.service().target().soap().action()).isEqualTo("urn:GetPerson");
    }

    @Test
    void soapFormatCanNotBeSetOnARestIntegration() throws Exception {
        send("POST", "/admin/api/integrations", Map.of("name", "Plain one"));
        HttpResponse<String> r = send("PUT", "/admin/api/integrations/plain-one/formats", Map.of("request", "soap"));
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("comes with a SOAP operation");
    }
}
