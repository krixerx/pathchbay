package integrationmapper.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A SOAP person registry served by the test app itself (D-30); remembers what it received and how often. */
@RestController
class FakeSoapApi {

    final Map<String, Object> lastRequest = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();

    private void remember(String body, HttpHeaders headers) {
        calls.incrementAndGet();
        lastRequest.put("body", body);
        lastRequest.put("headers", headers);
    }

    /** Answers with the code it was asked for, a ns2 prefix, and one phone number (a list of one). */
    @PostMapping("/fake-soap/person")
    ResponseEntity<String> person(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        remember(body, headers);
        Matcher m = Pattern.compile("personCode>([^<]*)<").matcher(body);
        String code = m.find() ? m.group(1) : "?";
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <S:Envelope xmlns:S="http://schemas.xmlsoap.org/soap/envelope/">
                  <S:Body>
                    <ns2:GetPersonResponse xmlns:ns2="urn:example:person" xmlns:c="urn:example:common">
                      <ns2:person>
                        <ns2:firstName>Mari</ns2:firstName>
                        <ns2:lastName>Maasikas-%s</ns2:lastName>
                        <ns2:children>2</ns2:children>
                        <ns2:address><c:street>Pikk 1</c:street><c:city>Tallinn</c:city></ns2:address>
                        <ns2:phones>5550001</ns2:phones>
                      </ns2:person>
                    </ns2:GetPersonResponse>
                  </S:Body>
                </S:Envelope>""".formatted(code);
        return ResponseEntity.ok().header("Content-Type", "text/xml; charset=utf-8").body(envelope);
    }

    @PostMapping("/fake-soap/fault500")
    ResponseEntity<String> fault500(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        remember(body, headers);
        return ResponseEntity.status(500).header("Content-Type", "text/xml; charset=utf-8").body("""
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <soap:Fault>
                      <faultcode>soap:Client</faultcode>
                      <faultstring>Unknown person</faultstring>
                      <detail><e:error xmlns:e="urn:example:errors"><e:code>404</e:code><e:field>personCode</e:field></e:error></detail>
                    </soap:Fault>
                  </soap:Body>
                </soap:Envelope>""");
    }

    @PostMapping("/fake-soap/fault200")
    ResponseEntity<String> fault200(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        remember(body, headers);
        return ResponseEntity.ok().header("Content-Type", "application/soap+xml; charset=utf-8").body("""
                <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
                  <env:Body>
                    <env:Fault>
                      <env:Code><env:Value>env:Sender</env:Value><env:Subcode><env:Value>m:BadCode</env:Value></env:Subcode></env:Code>
                      <env:Reason><env:Text xml:lang="en">Personal code is not valid</env:Text></env:Reason>
                    </env:Fault>
                  </env:Body>
                </env:Envelope>""");
    }

    @PostMapping("/fake-soap/html503")
    ResponseEntity<String> html503(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        remember(body, headers);
        return ResponseEntity.status(503).header("Content-Type", "text/html").body("<html><body>Down for maintenance</body></html>");
    }

    /**
     * The person WSDL the way SOAP stacks publish it: {@code ?wsdl} answers the WSDL, whose schema import points at
     * {@code ?xsd=1} on the same host; {@code ?xsd=1} answers the common schema.
     */
    @org.springframework.web.bind.annotation.GetMapping("/fake-soap/ws/person")
    ResponseEntity<String> wsdl(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        String q = request.getQueryString() == null ? "" : request.getQueryString();
        String self = request.getRequestURL().toString();
        if (q.equals("xsd=1")) {
            return ResponseEntity.ok().header("Content-Type", "text/xml").body(resource("/wsdl/person/schemas/common.xsd"));
        }
        if (q.equalsIgnoreCase("wsdl")) {
            String wsdl = resource("/wsdl/person/person.wsdl").replace("schemas/common.xsd", self + "?xsd=1")
                    .replace("http://registry.example/ws/person-rpc", "http://other.example/ws/person-rpc")
                    .replace("http://registry.example/ws/person", request.getRequestURL().toString().replace("/ws/person", "/person"));
            return ResponseEntity.ok().header("Content-Type", "text/xml").body(wsdl);
        }
        return ResponseEntity.notFound().build();
    }

    /** A WSDL whose schema URL is on the same host but redirects to another one, which must not be followed. */
    @org.springframework.web.bind.annotation.GetMapping("/fake-soap/ws/moved")
    ResponseEntity<String> moved(jakarta.servlet.http.HttpServletRequest request) throws java.io.IOException {
        String q = request.getQueryString() == null ? "" : request.getQueryString();
        if (q.equals("xsd=1")) {
            return ResponseEntity.status(302).header("Location", "http://127.0.0.2:9/elsewhere.xsd").build();
        }
        return ResponseEntity.ok().header("Content-Type", "text/xml")
                .body(resource("/wsdl/person/person.wsdl").replace("schemas/common.xsd", request.getRequestURL() + "?xsd=1"));
    }

    private static String resource(String path) throws java.io.IOException {
        try (var in = FakeSoapApi.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @PostMapping("/fake-soap/not-soap")
    ResponseEntity<String> notSoap(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        remember(body, headers);
        return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"hello\":\"world\"}");
    }
}
