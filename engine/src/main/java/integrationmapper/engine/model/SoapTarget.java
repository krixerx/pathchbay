package integrationmapper.engine.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import integrationmapper.engine.format.XmlFormat;

/**
 * What a SOAP call needs beyond method and URL (D-30), taken from the WSDL when the operation is chosen and copied
 * into every version file, so a published version never reads the contract again.
 * <p>
 * Paths name elements by where request links put them: {@code body} is the body element, {@code body.address} its
 * child {@code address}, and a list item has the path of its list ({@code body.phones}). {@code request.ns} gives
 * the namespace of every element path whose namespace differs from the body element's; {@code ""} means unqualified.
 * {@code request.order} gives the XSD sequence order of an object's children. {@code response.lists} are the list
 * paths of the answer in link notation ({@code persons[]}, {@code persons[].phones[]}).
 *
 * @param version {@code 1.1} or {@code 1.2}
 * @param action  the soapAction, may be empty
 * @param body    the element inside the SOAP Body, null when the input message has no part
 * @param address the port's address from the WSDL, only used to prefill the connection
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SoapTarget(String version, String action, Element body, String address, Request request, Response response) {

    public static final String SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/";
    public static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";
    static final Set<String> VERSIONS = Set.of("1.1", "1.2");

    /** An element name and its namespace ({@code ""} for none). */
    public record Element(String name, String ns) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Request(Map<String, String> ns, Map<String, List<String>> order) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Response(String element, List<String> lists) {
    }

    public boolean soap12() {
        return "1.2".equals(version);
    }

    public String envelopeNs() {
        return soap12() ? SOAP12 : SOAP11;
    }

    public Map<String, String> requestNs() {
        return request == null || request.ns() == null ? Map.of() : request.ns();
    }

    public Map<String, List<String>> requestOrder() {
        return request == null || request.order() == null ? Map.of() : request.order();
    }

    public List<String> responseLists() {
        return response == null || response.lists() == null ? List.of() : response.lists();
    }

    /** Structural checks at compile time; throws naming the integration. */
    public void check(String slug) {
        if (!VERSIONS.contains(version)) {
            throw new IllegalArgumentException("Integration " + slug + ": target.soap.version must be 1.1 or 1.2, not " + version);
        }
        if (body != null) {
            XmlFormat.checkRoot(body.name(), "SOAP body element");
        }
    }
}
