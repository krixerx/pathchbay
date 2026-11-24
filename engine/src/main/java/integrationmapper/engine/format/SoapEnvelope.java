package integrationmapper.engine.format;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import integrationmapper.engine.model.SoapTarget;

/**
 * The SOAP envelope around a mapped request, and the way back from an answer (D-30). Mapping stays JSON to JSON:
 * the request's {@code body} object is written inside the operation's body element with the namespaces and XSD
 * order of {@link SoapTarget}; an answer's first Body child is handed to {@link XmlFormat} by local names, or, when
 * it is a Fault, turned into a {@link Fault}.
 */
public final class SoapEnvelope {

    static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final XMLOutputFactory OUT = XMLOutputFactory.newInstance();

    private SoapEnvelope() {
    }

    /** The Content-Type of a request: SOAP 1.1 {@code text/xml}, SOAP 1.2 {@code application/soap+xml} with the action. */
    public static String contentType(SoapTarget soap) {
        if (soap.soap12()) {
            String action = soap.action() == null || soap.action().isEmpty() ? "" : "; action=\"" + soap.action() + "\"";
            return "application/soap+xml; charset=utf-8" + action;
        }
        return "text/xml; charset=utf-8";
    }

    /** The SOAPAction header of a SOAP 1.1 request, quoted as the WS-I Basic Profile asks; none for SOAP 1.2. */
    public static String soapAction(SoapTarget soap) {
        return soap.soap12() ? null : "\"" + (soap.action() == null ? "" : soap.action()) + "\"";
    }

    public static String accept(SoapTarget soap) {
        return soap.soap12() ? "application/soap+xml" : "text/xml";
    }

    // ---- writing

    /** The whole envelope for the mapped {@code body} (an object, a simple value, or missing). */
    public static String write(SoapTarget soap, JsonNode body) {
        XmlFormat.checkNames(body);
        StringWriter out = new StringWriter();
        try {
            XMLStreamWriter w = OUT.createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("soap", "Envelope", soap.envelopeNs());
            w.writeNamespace("soap", soap.envelopeNs());
            w.writeNamespace("xsi", XSI);
            w.writeStartElement("soap", "Body", soap.envelopeNs());
            if (soap.body() != null) {
                Map<String, String> prefixes = prefixes(soap);
                new Writer(w, soap, prefixes).element(soap.body().name(), "body", body == null ? null : body, true);
            }
            w.writeEndElement();
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("Could not write the SOAP request: " + e.getMessage(), e);
        }
        return out.toString();
    }

    /** One prefix per namespace used in the request, declared on the body element: ns1, ns2, ... */
    private static Map<String, String> prefixes(SoapTarget soap) {
        Set<String> all = new LinkedHashSet<>();
        if (!soap.body().ns().isEmpty()) {
            all.add(soap.body().ns());
        }
        soap.requestNs().values().stream().filter(ns -> !ns.isEmpty()).forEach(all::add);
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        for (String ns : all) {
            out.put(ns, "ns" + i++);
        }
        return out;
    }

    private record Writer(XMLStreamWriter w, SoapTarget soap, Map<String, String> prefixes) {

        String ns(String path) {
            return path.equals("body") ? soap.body().ns() : soap.requestNs().getOrDefault(path, soap.body().ns());
        }

        /** Writes {@code value} as element {@code name} at {@code path}; a list writes one element per item. */
        void element(String name, String path, JsonNode value, boolean top) throws XMLStreamException {
            if (value != null && value.isArray()) {
                for (JsonNode item : value) {
                    element(name, path, item, top);
                }
                return;
            }
            String ns = ns(path);
            if (value == null || value.isMissingNode()) {
                start(name, ns, top);
                w.writeEndElement();
                return;
            }
            start(name, ns, top);
            if (value.isNull()) {
                w.writeAttribute("xsi", XSI, "nil", "true");
            } else if (value.isObject()) {
                for (String child : ordered((ObjectNode) value, soap.requestOrder().get(path))) {
                    element(child, path + "." + child, value.get(child), false);
                }
            } else {
                w.writeCharacters(value.asText());
            }
            w.writeEndElement();
        }

        private void start(String name, String ns, boolean top) throws XMLStreamException {
            if (ns == null || ns.isEmpty()) {
                w.writeStartElement(name);
            } else {
                w.writeStartElement(prefixes.get(ns), name, ns);
            }
            if (top) {
                for (Map.Entry<String, String> p : prefixes.entrySet()) {
                    w.writeNamespace(p.getValue(), p.getKey());
                }
            }
        }

        /** The object's keys in XSD sequence order; keys the sequence does not know come after, as mapped. */
        private static List<String> ordered(ObjectNode obj, List<String> order) {
            List<String> out = new ArrayList<>();
            if (order != null) {
                for (String n : order) {
                    if (obj.has(n)) {
                        out.add(n);
                    }
                }
            }
            for (Iterator<String> it = obj.fieldNames(); it.hasNext(); ) {
                String n = it.next();
                if (!out.contains(n)) {
                    out.add(n);
                }
            }
            return out;
        }
    }

    /**
     * A mock answer for test runs (D-30): {@code example} (built from the response tree) as the response element
     * inside an envelope of the operation's SOAP version; an empty Body when there is no response element.
     */
    public static String mockAnswer(SoapTarget soap, JsonNode example) {
        String inner = "";
        if (example != null && soap.response() != null && soap.response().element() != null) {
            inner = XmlFormat.write(example, soap.response().element()).replaceFirst("^<\\?xml[^>]*\\?>", "");
        }
        return "<soap:Envelope xmlns:soap=\"" + soap.envelopeNs() + "\"><soap:Body>" + inner + "</soap:Body></soap:Envelope>";
    }

    // ---- reading

    /** A SOAP Fault: its code, reason and detail (as JSON, null when there is none). */
    public record Fault(String code, String reason, JsonNode detail) {

        public ObjectNode toJson() {
            ObjectNode n = Json.MAPPER.createObjectNode();
            n.put("code", code);
            n.put("reason", reason);
            n.set("detail", detail);
            return n;
        }
    }

    /** An answer that is a SOAP envelope: the XML of the first Body child, or the Fault in it. */
    public record Answer(String bodyXml, Fault fault) {
    }

    /**
     * Reads an answer as a SOAP envelope. Null when it is not one (not XML, or another root), so the caller can
     * treat it as an ordinary answer. An envelope with an empty Body answers an empty {@code bodyXml}.
     */
    public static Answer read(String text) {
        if (text == null || !text.stripLeading().startsWith("<")) {
            return null;
        }
        Document doc;
        try {
            doc = SafeXml.parse(text, "The answer");
        } catch (IllegalArgumentException e) {
            return null;
        }
        Element env = doc.getDocumentElement();
        String ns = env.getNamespaceURI();
        if (!"Envelope".equals(env.getLocalName()) || !(SoapTarget.SOAP11.equals(ns) || SoapTarget.SOAP12.equals(ns))) {
            return null;
        }
        Element body = child(env, ns, "Body");
        Element first = body == null ? null : firstElement(body);
        if (first == null) {
            return new Answer("", null);
        }
        if ("Fault".equals(first.getLocalName()) && ns.equals(first.getNamespaceURI())) {
            return new Answer(null, SoapTarget.SOAP11.equals(ns) ? fault11(first) : fault12(first, ns));
        }
        return new Answer(SafeXml.toText(first), null);
    }

    private static Fault fault11(Element f) {
        // SOAP 1.1 fault children are unqualified.
        return new Fault(text(child(f, null, "faultcode")), text(child(f, null, "faultstring")), detail(child(f, null, "detail")));
    }

    private static Fault fault12(Element f, String ns) {
        Element code = child(f, ns, "Code");
        String value = text(code == null ? null : child(code, ns, "Value"));
        Element sub = code == null ? null : child(code, ns, "Subcode");
        String subValue = text(sub == null ? null : child(sub, ns, "Value"));
        Element reason = child(f, ns, "Reason");
        return new Fault(subValue == null ? value : value + " / " + subValue, text(reason == null ? null : child(reason, ns, "Text")),
                detail(child(f, ns, "Detail")));
    }

    private static JsonNode detail(Element d) {
        if (d == null || firstElement(d) == null) {
            return d == null || d.getTextContent().isBlank() ? null : Json.MAPPER.getNodeFactory().textNode(d.getTextContent().strip());
        }
        try {
            return XmlFormat.read(SafeXml.toText(d), List.of());
        } catch (IllegalArgumentException e) {
            return Json.MAPPER.getNodeFactory().textNode(d.getTextContent().strip());
        }
    }

    private static String text(Element e) {
        return e == null ? null : e.getTextContent().strip();
    }

    /** The first child element with this local name; {@code ns} null matches any namespace. */
    private static Element child(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName()) && (ns == null || ns.equals(e.getNamespaceURI()))) {
                return e;
            }
        }
        return null;
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }
}
