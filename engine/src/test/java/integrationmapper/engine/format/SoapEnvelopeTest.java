package integrationmapper.engine.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import integrationmapper.engine.model.SoapTarget;

/** Writing and reading SOAP envelopes (D-30, spec soap-outbound-call). */
class SoapEnvelopeTest {

    static final String HEAD = "<?xml version='1.0' encoding='UTF-8'?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><soap:Body>";
    static final String TAIL = "</soap:Body></soap:Envelope>";

    static String write(SoapTarget soap, String json) throws Exception {
        return SoapEnvelope.write(soap, json == null ? null : Json.MAPPER.readTree(json));
    }

    @Test
    void elementsFromAnotherNamespaceGetTheirOwnPrefixAndOrderFollowsTheSequence() throws Exception {
        SoapTarget soap = new SoapTarget("1.1", "urn:SavePerson", new SoapTarget.Element("SavePerson", "urn:example:person"), null,
                new SoapTarget.Request(
                        Map.of("body.address.street", "urn:example:common", "body.address.city", "urn:example:common"),
                        Map.of("body", List.of("firstName", "lastName", "address"), "body.address", List.of("street", "city"))),
                null);

        String xml = write(soap, "{\"address\":{\"city\":\"Tartu\",\"street\":\"Ülikooli 18\"},\"lastName\":\"Tamm\",\"firstName\":\"Jaan\"}");

        assertThat(xml).isEqualTo(HEAD + "<ns1:SavePerson xmlns:ns1=\"urn:example:person\" xmlns:ns2=\"urn:example:common\">"
                + "<ns1:firstName>Jaan</ns1:firstName><ns1:lastName>Tamm</ns1:lastName>"
                + "<ns1:address><ns2:street>Ülikooli 18</ns2:street><ns2:city>Tartu</ns2:city></ns1:address>"
                + "</ns1:SavePerson>" + TAIL);
    }

    @Test
    void unqualifiedChildrenHaveNoNamespaceAndListsAreRepeatedElements() throws Exception {
        SoapTarget soap = new SoapTarget("1.1", "", new SoapTarget.Element("PlaceOrder", "urn:example:order"), null,
                new SoapTarget.Request(Map.of("body.id", "", "body.lines", "", "body.lines.sku", "", "body.lines.qty", ""),
                        Map.of("body", List.of("id", "lines"), "body.lines", List.of("sku", "qty"))),
                null);

        String xml = write(soap, "{\"lines\":[{\"qty\":1,\"sku\":\"A\"},{\"sku\":\"B\",\"qty\":2}],\"id\":\"O-1\"}");

        assertThat(xml).isEqualTo(HEAD + "<ns1:PlaceOrder xmlns:ns1=\"urn:example:order\"><id>O-1</id>"
                + "<lines><sku>A</sku><qty>1</qty></lines><lines><sku>B</sku><qty>2</qty></lines></ns1:PlaceOrder>" + TAIL);
    }

    /** Keys come from callers when an object is linked as a whole; one that isn't an XML name is refused, not written. */
    @Test
    void aKeyThatIsNoXmlNameIsRefused() {
        SoapTarget soap = new SoapTarget("1.1", "", new SoapTarget.Element("SavePerson", "urn:example:person"), null, null, null);
        assertThatThrownBy(() -> write(soap, "{\"person\":{\"x/><Admin>true</Admin><y\":{}}}"))
                .isInstanceOf(XmlFormat.BadNameException.class).hasMessageContaining("person.x/><Admin>");
    }

    @Test
    void anEmptyRequestStillSendsTheBodyElementAndNoPartSendsAnEmptyBody() throws Exception {
        SoapTarget soap = new SoapTarget("1.1", "", new SoapTarget.Element("Ping", ""), null, null, null);
        assertThat(write(soap, "{}")).isEqualTo(HEAD + "<Ping/>" + TAIL);
        assertThat(write(soap, null)).isEqualTo(HEAD + "<Ping/>" + TAIL);

        SoapTarget none = new SoapTarget("1.1", "", null, null, null, null);
        assertThat(write(none, "{}")).isEqualTo(HEAD.replace("<soap:Body>", "<soap:Body/>") + "</soap:Envelope>");
    }

    @Test
    void aSimpleBodyElementCarriesTheValueAsText() throws Exception {
        SoapTarget soap = new SoapTarget("1.1", "", new SoapTarget.Element("Echo", "urn:e"), null, null, null);
        assertThat(write(soap, "\"a < b & c\"")).isEqualTo(HEAD + "<ns1:Echo xmlns:ns1=\"urn:e\">a &lt; b &amp; c</ns1:Echo>" + TAIL);
    }

    @Test
    void soap12UsesItsEnvelopeNamespaceAndTransportHeaders() throws Exception {
        SoapTarget soap = new SoapTarget("1.2", "urn:Ping", new SoapTarget.Element("Ping", ""), null, null, null);

        assertThat(write(soap, "{}")).contains("xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"");
        assertThat(SoapEnvelope.contentType(soap)).isEqualTo("application/soap+xml; charset=utf-8; action=\"urn:Ping\"");
        assertThat(SoapEnvelope.soapAction(soap)).isNull();
        SoapTarget soap11 = new SoapTarget("1.1", "", null, null, null, null);
        assertThat(SoapEnvelope.contentType(soap11)).isEqualTo("text/xml; charset=utf-8");
        assertThat(SoapEnvelope.soapAction(soap11)).isEqualTo("\"\"");
    }

    @Test
    void anAnswerIsReadByLocalNamesAndARepeatedElementFromTheXsdIsAListWithoutALink() {
        SoapEnvelope.Answer a = SoapEnvelope.read("""
                <S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/"><S:Body>
                  <ns2:R xmlns:ns2="urn:x"><ns2:person><ns2:name>A</ns2:name><ns2:phones>1</ns2:phones></ns2:person></ns2:R>
                </S:Body></S:Envelope>""");

        assertThat(a.fault()).isNull();
        // The lists come from the XSD (soap.response.lists), not from a link into them.
        assertThat(XmlFormat.read(a.bodyXml(), XmlFormat.listPaths(List.of("person.phones[]"))).toString())
                .isEqualTo("{\"person\":{\"name\":\"A\",\"phones\":[\"1\"]}}");
    }

    @Test
    void prefixesDeclaredOnlyOnTheEnvelopeStillWork() {
        SoapEnvelope.Answer a = SoapEnvelope.read("""
                <S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns2="urn:x"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><S:Body>
                  <ns2:R><ns2:person><ns2:name>A</ns2:name><ns2:nick xsi:nil="true"/></ns2:person></ns2:R>
                </S:Body></S:Envelope>""");

        assertThat(XmlFormat.read(a.bodyXml(), List.of()).toString()).isEqualTo("{\"person\":{\"name\":\"A\",\"nick\":null}}");
    }

    @Test
    void notAnEnvelopeIsNull() {
        assertThat(SoapEnvelope.read("{\"a\":1}")).isNull();
        assertThat(SoapEnvelope.read("<html/>")).isNull();
        assertThat(SoapEnvelope.read("<broken")).isNull();
        assertThat(SoapEnvelope.read("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>")).isNull();
    }

    @Test
    void anEmptyBodyIsAnEmptyAnswer() {
        SoapEnvelope.Answer a = SoapEnvelope.read("<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\"><e:Body/></e:Envelope>");
        assertThat(a.bodyXml()).isEmpty();
        assertThat(a.fault()).isNull();
    }

    @Test
    void faultsOfBothVersionsAreRead() throws Exception {
        SoapEnvelope.Fault f11 = SoapEnvelope.read("""
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
                <faultcode>soap:Server</faultcode><faultstring> Database down </faultstring></soap:Fault></soap:Body></soap:Envelope>""").fault();
        assertThat(f11).isEqualTo(new SoapEnvelope.Fault("soap:Server", "Database down", null));

        SoapEnvelope.Fault f12 = SoapEnvelope.read("""
                <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"><env:Body><env:Fault>
                <env:Code><env:Value>env:Receiver</env:Value></env:Code><env:Reason><env:Text xml:lang="en">Busy</env:Text></env:Reason>
                <env:Detail><t:retry xmlns:t="urn:t">30</t:retry></env:Detail></env:Fault></env:Body></env:Envelope>""").fault();
        assertThat(f12.code()).isEqualTo("env:Receiver");
        assertThat(f12.reason()).isEqualTo("Busy");
        assertThat(f12.detail()).isEqualTo(Json.MAPPER.readTree("{\"retry\":\"30\"}"));
    }

    @Test
    void theMockAnswerIsAnEnvelopeAroundTheResponseElement() throws Exception {
        SoapTarget soap = new SoapTarget("1.1", "", null, null, null, new SoapTarget.Response("GetPersonResponse", null));

        String mock = SoapEnvelope.mockAnswer(soap, Json.MAPPER.readTree("{\"person\":{\"name\":\"name 1\"}}"));

        assertThat(mock).isEqualTo("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                + "<GetPersonResponse><person><name>name 1</name></person></GetPersonResponse></soap:Body></soap:Envelope>");
        assertThat(SoapEnvelope.read(mock).bodyXml()).contains("<name>name 1</name>");
    }
}
