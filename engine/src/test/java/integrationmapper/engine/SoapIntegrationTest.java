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
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

/** SOAP calls through the real Camel routes (D-30, spec soap-outbound-call). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mapper.config-dir=target/no-config", "MAPPER_SECRET_SOAP_BASIC=svc:pa55", "MAPPER_SECRET_SOAP_BAD_SECRET=nocolon"})
class SoapIntegrationTest {

    static final String QUERY = """
            {"id":"person-query","fields":[
              {"id":"pq-code","name":"personCode","type":"text","required":true},
              {"id":"pq-history","name":"history","type":"boolean"},
              {"id":"pq-nick","name":"nickname","type":"text"}]}""";
    static final String CARD = """
            {"id":"person-card","fields":[
              {"id":"pc-name","name":"name","type":"text"},
              {"id":"pc-surname","name":"surname","type":"text"},
              {"id":"pc-kids","name":"kids","type":"integer"},
              {"id":"pc-city","name":"city","type":"text"},
              {"id":"pc-phones","name":"phones","type":"list","items":{"type":"text"}}]}""";

    static SoapTarget getPerson(String version) {
        return new SoapTarget(version, "urn:GetPerson", new SoapTarget.Element("GetPerson", "urn:example:person"), null,
                new SoapTarget.Request(null, Map.of("body", List.of("personCode", "includeHistory", "nickname"))),
                new SoapTarget.Response("GetPersonResponse", List.of("person.phones[]")));
    }

    @Autowired
    IntegrationPublisher publisher;

    @Autowired
    PayloadLibrary library;

    @Autowired
    FakeSoapApi fake;

    @Autowired
    CallLog calls;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String fake(String endpoint) {
        return "http://localhost:" + port + "/fake-soap/" + endpoint;
    }

    ServiceDefinition soapIntegration(String slug, String endpoint, SoapTarget soap, ServiceDefinition.Auth auth) {
        return new ServiceDefinition(slug, "1.0.0", null, "person-query", "person-card",
                new ServiceDefinition.Target(fake(endpoint), "POST", "", null, soap), auth,
                // Links in another order than the XSD sequence: the envelope must still follow the sequence.
                new ServiceDefinition.Mapping(List.of(link("pq-nick", "body.nickname"), link("pq-history", "body.includeHistory"),
                        link("pq-code", "body.personCode"))),
                new ServiceDefinition.Mapping(List.of(link("person.firstName", "pc-name"), link("person.lastName", "pc-surname"),
                        link("person.children", "pc-kids"), link("person.address.city", "pc-city"), link("person.phones[]", "pc-phones"))),
                new ServiceDefinition.Formats("soap", null, null, null));
    }

    @BeforeEach
    void definitions() throws Exception {
        library.put(definition(QUERY));
        library.put(definition(CARD));
        fake.lastRequest.clear();
        fake.calls.set(0);
    }

    /** The newest call log entry of an integration; the log is written right after the answer, so wait briefly. */
    JsonNode lastCall(String slug) throws Exception {
        for (int i = 0; i < 100 && calls.list(slug).isEmpty(); i++) {
            Thread.sleep(20);
        }
        return Json.MAPPER.valueToTree(calls.list(slug).get(0));
    }

    HttpResponse<String> call(String slug, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void soap11SendsANamespacedEnvelopeInSequenceOrderAndMapsTheAnswer() throws Exception {
        publisher.publish(soapIntegration("soap-get", "person", getPerson("1.1"), null));

        HttpResponse<String> r = call("soap-get", "{\"history\":true,\"personCode\":\"38001010000\",\"nickname\":null}");

        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Content-Type")).isEqualTo("text/xml; charset=utf-8");
        assertThat(sent.getFirst("SOAPAction")).isEqualTo("\"urn:GetPerson\"");
        assertThat(fake.lastRequest.get("body")).isEqualTo("<?xml version='1.0' encoding='UTF-8'?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<soap:Body><ns1:GetPerson xmlns:ns1=\"urn:example:person\">"
                + "<ns1:personCode>38001010000</ns1:personCode><ns1:includeHistory>true</ns1:includeHistory>"
                + "<ns1:nickname xsi:nil=\"true\"/>"
                + "</ns1:GetPerson></soap:Body></soap:Envelope>");
        // Prefixes in the answer don't matter; one phone number is still a list; numbers get the Output's types.
        assertThat(Json.MAPPER.readTree(r.body())).isEqualTo(Json.MAPPER.readTree("""
                {"name":"Mari","surname":"Maasikas-38001010000","kids":2,"city":"Tallinn","phones":["5550001"]}"""));
        assertThat(fake.calls.get()).isEqualTo(1);
    }

    @Test
    void aFieldTheCallerLeavesOutIsLeftOutOfTheEnvelope() throws Exception {
        publisher.publish(soapIntegration("soap-get", "person", getPerson("1.1"), null));

        call("soap-get", "{\"personCode\":\"1\"}");

        assertThat((String) fake.lastRequest.get("body")).contains("<ns1:personCode>1</ns1:personCode></ns1:GetPerson>")
                .doesNotContain("includeHistory").doesNotContain("nickname");
    }

    @Test
    void soap12PutsTheActionInTheContentType() throws Exception {
        publisher.publish(soapIntegration("soap-get12", "person", getPerson("1.2"), null));

        HttpResponse<String> r = call("soap-get12", "{\"personCode\":\"1\"}");

        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Content-Type")).isEqualTo("application/soap+xml; charset=utf-8; action=\"urn:GetPerson\"");
        assertThat(sent.containsHeader("SOAPAction")).isFalse();
        assertThat((String) fake.lastRequest.get("body")).contains("<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"");
        assertThat(r.statusCode()).isEqualTo(200); // the fake answers SOAP 1.1; the answer is read either way
    }

    @Test
    void aFaultOn500BecomesARemoteFaultProblemAndIsSentOnce() throws Exception {
        publisher.publish(soapIntegration("soap-fault", "fault500", getPerson("1.1"), null));

        HttpResponse<String> r = call("soap-fault", "{\"personCode\":\"1\"}");

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.headers().firstValue("X-Integration-Error")).hasValue("remote");
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").asText()).isEqualTo("remote-fault");
        assertThat(body.path("integration").asText()).isEqualTo("soap-fault");
        assertThat(body.path("detail").asText()).isEqualTo("Unknown person");
        assertThat(body.path("fault").path("code").asText()).isEqualTo("soap:Client");
        assertThat(body.path("fault").path("reason").asText()).isEqualTo("Unknown person");
        assertThat(body.path("fault").path("detail")).isEqualTo(Json.MAPPER.readTree("{\"error\":{\"code\":\"404\",\"field\":\"personCode\"}}"));
        assertThat(fake.calls.get()).isEqualTo(1);
    }

    @Test
    void aFaultOn200Gives502AndNoMapping() throws Exception {
        publisher.publish(soapIntegration("soap-fault200", "fault200", getPerson("1.2"), null));

        HttpResponse<String> r = call("soap-fault200", "{\"personCode\":\"1\"}");

        assertThat(r.statusCode()).isEqualTo(502);
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").asText()).isEqualTo("remote-fault");
        assertThat(body.path("fault").path("code").asText()).isEqualTo("env:Sender / m:BadCode");
        assertThat(body.path("fault").path("reason").asText()).isEqualTo("Personal code is not valid");
        assertThat(body.has("name")).isFalse();
    }

    @Test
    void anErrorThatIsNotSoapPassesThroughUnchanged() throws Exception {
        publisher.publish(soapIntegration("soap-down", "html503", getPerson("1.1"), null));

        HttpResponse<String> r = call("soap-down", "{\"personCode\":\"1\"}");

        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("X-Integration-Error")).hasValue("remote");
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/html"));
        assertThat(r.body()).isEqualTo("<html><body>Down for maintenance</body></html>");
    }

    @Test
    void aSuccessfulAnswerThatIsNotSoapIsAMappingError() throws Exception {
        publisher.publish(soapIntegration("soap-json", "not-soap", getPerson("1.1"), null));

        HttpResponse<String> r = call("soap-json", "{\"personCode\":\"1\"}");

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).contains("not a SOAP envelope");
    }

    @Test
    void invalidInputNeverReachesTheSoapService() throws Exception {
        publisher.publish(soapIntegration("soap-get", "person", getPerson("1.1"), null));

        HttpResponse<String> r = call("soap-get", "{\"history\":\"maybe\"}");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("personCode").contains("history");
        assertThat(fake.calls.get()).isZero();
    }

    @Test
    void theCallLogShowsBothEnvelopesAsText() throws Exception {
        publisher.publish(soapIntegration("soap-logged", "person", getPerson("1.1"), null));

        call("soap-logged", "{\"personCode\":\"7\"}");

        JsonNode last = lastCall("soap-logged");
        assertThat(last.path("request").path("body").asText()).startsWith("<?xml").contains("<ns1:personCode>7</ns1:personCode>");
        assertThat(last.path("request").path("headers").path("SOAPAction").asText()).isEqualTo("\"urn:GetPerson\"");
        assertThat(last.path("response").path("body").asText()).contains("GetPersonResponse");
    }

    @Test
    void basicAuthSendsTheSecretAsUsernameAndPassword() throws Exception {
        publisher.publish(soapIntegration("soap-basic", "person", getPerson("1.1"), new ServiceDefinition.Auth("basic", null, null)));

        call("soap-basic", "{\"personCode\":\"1\"}");

        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Authorization")).isEqualTo("Basic c3ZjOnBhNTU=");
        assertThat(lastCall("soap-basic").path("request").path("headers").path("Authorization").asText()).isEqualTo("***");
    }

    @Test
    void basicAuthWithoutAColonDoesNotPublish() {
        assertThatThrownBy(() -> publisher.publish(soapIntegration("soap-bad-secret", "person", getPerson("1.1"),
                new ServiceDefinition.Auth("basic", null, null))))
                .hasMessageContaining("username:password");
    }

    @Test
    void soapFormatAndSoapTargetGoTogether() {
        ServiceDefinition noTarget = soapIntegration("soap-x", "person", null, null);
        assertThatThrownBy(() -> publisher.publish(noTarget)).hasMessageContaining("format soap and target.soap go together");

        ServiceDefinition d = soapIntegration("soap-x", "person", getPerson("1.1"), null);
        ServiceDefinition put = new ServiceDefinition(d.id(), d.version(), null, d.input(), d.output(),
                new ServiceDefinition.Target(fake("person"), "PUT", "", null, getPerson("1.1")), null, d.request(), d.response(), d.formats());
        assertThatThrownBy(() -> publisher.publish(put)).hasMessageContaining("called with POST");

        ServiceDefinition badVersion = soapIntegration("soap-x", "person", new SoapTarget("1.3", "", null, null, null, null), null);
        assertThatThrownBy(() -> publisher.publish(badVersion)).hasMessageContaining("1.1 or 1.2");
    }
}
