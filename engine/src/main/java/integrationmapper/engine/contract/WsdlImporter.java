package integrationmapper.engine.contract;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.namespace.QName;

import org.apache.ws.commons.schema.XmlSchemaAll;
import org.apache.ws.commons.schema.XmlSchemaAny;
import org.apache.ws.commons.schema.XmlSchemaChoice;
import org.apache.ws.commons.schema.XmlSchemaCollection;
import org.apache.ws.commons.schema.XmlSchemaComplexContentExtension;
import org.apache.ws.commons.schema.XmlSchemaComplexContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaComplexType;
import org.apache.ws.commons.schema.XmlSchemaContent;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.apache.ws.commons.schema.XmlSchemaGroupRef;
import org.apache.ws.commons.schema.XmlSchemaParticle;
import org.apache.ws.commons.schema.XmlSchemaSequence;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentExtension;
import org.apache.ws.commons.schema.XmlSchemaSimpleContentRestriction;
import org.apache.ws.commons.schema.XmlSchemaSimpleType;
import org.apache.ws.commons.schema.XmlSchemaSimpleTypeRestriction;
import org.apache.ws.commons.schema.XmlSchemaType;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import integrationmapper.engine.format.SafeXml;
import integrationmapper.engine.model.PayloadDefinition;
import integrationmapper.engine.model.SoapOperation;
import integrationmapper.engine.model.SoapTarget;

/**
 * Reads a WSDL 1.1 contract and its XSDs into SOAP operations with field trees (D-30, spec FR-002, FR-004). Every
 * port with a SOAP 1.1 or 1.2 binding lists its operations as {@code <port>/<operation>}; only document/literal
 * operations whose message parts refer to elements can be chosen, the others are listed with the reason. XSD
 * constructs outside the supported subset become warnings, like oneOf/anyOf in the OpenAPI importer.
 */
final class WsdlImporter {

    static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    static final String WSDL2_NS = "http://www.w3.org/ns/wsdl";
    static final String SOAP11_BINDING = "http://schemas.xmlsoap.org/wsdl/soap/";
    static final String SOAP12_BINDING = "http://schemas.xmlsoap.org/wsdl/soap12/";
    static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    private static final int MAX_DEPTH = 12;

    /** The operations, the port addresses and what the import had to skip. */
    record Result(List<SoapOperation> operations, List<String> servers, List<String> warnings) {
    }

    private record Part(String name, QName element, QName type) {
    }

    private record PortTypeOp(String name, QName input, QName output, String documentation) {
    }

    private record BindingOp(String action, String style, String inputUse, String outputUse, boolean headers) {
    }

    private record Binding(QName portType, String soapVersion, String style, Map<String, BindingOp> operations) {
    }

    private record Port(String name, QName binding, String address) {
    }

    private final SafeXml.Sources sources;
    private final List<String> warnings = new ArrayList<>();
    private final Map<QName, List<Part>> messages = new LinkedHashMap<>();
    private final Map<QName, List<PortTypeOp>> portTypes = new LinkedHashMap<>();
    private final Map<QName, Binding> bindings = new LinkedHashMap<>();
    private final List<Port> ports = new ArrayList<>();
    private final XmlSchemaCollection schemas = new XmlSchemaCollection();
    private final Set<String> readWsdls = new HashSet<>();

    private WsdlImporter(SafeXml.Sources sources) {
        this.sources = sources;
    }

    /** Whether a text looks like a WSDL (1.1 or 2.0), so the import call can send it here. */
    static boolean looksLikeWsdl(String text) {
        String t = text.stripLeading();
        return t.startsWith("<") && (t.contains(WSDL_NS) || t.contains(WSDL2_NS));
    }

    /**
     * Imports the WSDL stored under {@code mainKey} in {@code sources} (already read as {@code main}).
     * Throws IllegalArgumentException when the main file is not a WSDL 1.1 document or a schema is unsafe.
     */
    static Result read(String mainKey, byte[] main, SafeXml.Sources sources) {
        WsdlImporter w = new WsdlImporter(sources);
        sources.main(mainKey, main);
        Document doc = SafeXml.parse(main, "The WSDL");
        Element root = doc.getDocumentElement();
        if (WSDL2_NS.equals(root.getNamespaceURI())) {
            throw new IllegalArgumentException("WSDL 2.0 is not supported yet; give the service's WSDL 1.1 document");
        }
        if (!WSDL_NS.equals(root.getNamespaceURI()) || !"definitions".equals(root.getLocalName())) {
            throw new IllegalArgumentException("Not a WSDL 1.1 document (its root must be wsdl:definitions)");
        }
        w.schemas.setSchemaResolver((targetNamespace, schemaLocation, baseUri) -> w.resolveSchema(targetNamespace, schemaLocation, baseUri));
        w.readDefinitions(root, mainKey);
        return w.operations();
    }

    // ---- WSDL documents

    private void readDefinitions(Element defs, String key) {
        if (!readWsdls.add(key)) {
            return;
        }
        String tns = defs.getAttribute("targetNamespace");
        for (Element imp : children(defs, WSDL_NS, "import")) {
            String resolved = sources.resolve(imp.getAttribute("location"), key);
            byte[] bytes = resolved == null ? null : sources.read(resolved);
            if (bytes != null) {
                Element r = SafeXml.parse(bytes, "WSDL " + imp.getAttribute("location")).getDocumentElement();
                if (WSDL_NS.equals(r.getNamespaceURI())) {
                    readDefinitions(r, resolved);
                } else if (XSD_NS.equals(r.getNamespaceURI())) {
                    readSchema(r, resolved);
                }
            }
        }
        for (Element types : children(defs, WSDL_NS, "types")) {
            for (Element schema : children(types, XSD_NS, "schema")) {
                readSchema(schema, key);
            }
        }
        for (Element m : children(defs, WSDL_NS, "message")) {
            List<Part> parts = new ArrayList<>();
            for (Element p : children(m, WSDL_NS, "part")) {
                parts.add(new Part(p.getAttribute("name"), qname(p, p.getAttribute("element")), qname(p, p.getAttribute("type"))));
            }
            messages.put(new QName(tns, m.getAttribute("name")), parts);
        }
        for (Element pt : children(defs, WSDL_NS, "portType")) {
            List<PortTypeOp> ops = new ArrayList<>();
            for (Element op : children(pt, WSDL_NS, "operation")) {
                Element in = first(op, WSDL_NS, "input");
                Element out = first(op, WSDL_NS, "output");
                Element doc = first(op, WSDL_NS, "documentation");
                ops.add(new PortTypeOp(op.getAttribute("name"), in == null ? null : qname(in, in.getAttribute("message")),
                        out == null ? null : qname(out, out.getAttribute("message")), doc == null ? null : doc.getTextContent().strip()));
            }
            portTypes.put(new QName(tns, pt.getAttribute("name")), ops);
        }
        for (Element b : children(defs, WSDL_NS, "binding")) {
            Element sb = first(b, SOAP11_BINDING, "binding");
            String version = "1.1";
            if (sb == null) {
                sb = first(b, SOAP12_BINDING, "binding");
                version = "1.2";
            }
            String ns = version.equals("1.1") ? SOAP11_BINDING : SOAP12_BINDING;
            Map<String, BindingOp> ops = new LinkedHashMap<>();
            for (Element op : children(b, WSDL_NS, "operation")) {
                Element so = first(op, ns, "operation");
                Element in = first(op, WSDL_NS, "input");
                Element out = first(op, WSDL_NS, "output");
                Element inBody = in == null ? null : first(in, ns, "body");
                Element outBody = out == null ? null : first(out, ns, "body");
                boolean headers = (in != null && first(in, ns, "header") != null) || (out != null && first(out, ns, "header") != null);
                ops.put(op.getAttribute("name"), new BindingOp(so == null ? "" : so.getAttribute("soapAction"),
                        so == null || so.getAttribute("style").isEmpty() ? null : so.getAttribute("style"),
                        inBody == null ? null : inBody.getAttribute("use"), outBody == null ? null : outBody.getAttribute("use"), headers));
            }
            bindings.put(new QName(tns, b.getAttribute("name")), new Binding(qname(b, b.getAttribute("type")),
                    sb == null ? null : version, sb == null || sb.getAttribute("style").isEmpty() ? "document" : sb.getAttribute("style"), ops));
        }
        for (Element s : children(defs, WSDL_NS, "service")) {
            for (Element p : children(s, WSDL_NS, "port")) {
                Element addr = first(p, SOAP11_BINDING, "address");
                if (addr == null) {
                    addr = first(p, SOAP12_BINDING, "address");
                }
                ports.add(new Port(p.getAttribute("name"), qname(p, p.getAttribute("binding")), addr == null ? null : addr.getAttribute("location")));
            }
        }
    }

    /** Reads one schema into the collection; an inline schema gets the namespace declarations of its WSDL. */
    private void readSchema(Element schema, String key) {
        Document copy = SafeXml.builder().newDocument();
        Element s = (Element) copy.importNode(schema, true);
        copy.appendChild(s);
        for (Node n = schema.getParentNode(); n instanceof Element parent; n = n.getParentNode()) {
            NamedNodeMap attrs = parent.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr a = (Attr) attrs.item(i);
                if ("http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI()) && !s.hasAttributeNS(a.getNamespaceURI(), a.getLocalName())) {
                    s.setAttributeNS(a.getNamespaceURI(), a.getName(), a.getValue());
                }
            }
        }
        try {
            schemas.read(copy, key);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            warnings.add("A schema in " + key + " could not be read: " + e.getMessage());
        }
    }

    /**
     * Gives XmlSchema an import or include: parsed here with DTDs off, or an empty schema with a warning when the
     * location may not or can not be read, so the import goes on and the affected fields become text.
     */
    private InputSource resolveSchema(String targetNamespace, String location, String baseUri) {
        String key = sources.resolve(location, baseUri);
        byte[] bytes = key == null ? null : sources.read(key);
        String text;
        if (bytes == null) {
            text = "<xs:schema xmlns:xs=\"" + XSD_NS + "\"" + (targetNamespace == null || targetNamespace.isEmpty() ? ""
                    : " targetNamespace=\"" + targetNamespace.replace("\"", "&quot;") + "\"") + "/>";
        } else {
            text = SafeXml.toText(SafeXml.parse(bytes, "Schema " + location));
        }
        InputSource in = new InputSource(new StringReader(text));
        in.setSystemId(key == null ? (baseUri == null ? "missing" : baseUri) + "#missing-" + location : key);
        return in;
    }

    // ---- operations

    private Result operations() {
        List<SoapOperation> out = new ArrayList<>();
        Set<String> servers = new LinkedHashSet<>();
        for (Port port : ports) {
            Binding b = bindings.get(port.binding());
            if (b == null) {
                warnings.add("Port " + port.name() + " refers to binding " + port.binding().getLocalPart() + ", which is not in the WSDL");
                continue;
            }
            if (port.address() != null && !port.address().isBlank()) {
                servers.add(port.address());
            }
            List<PortTypeOp> ops = portTypes.getOrDefault(b.portType(), List.of());
            for (PortTypeOp op : ops) {
                out.add(operation(port, b, op));
            }
        }
        if (servers.size() > 1) {
            warnings.add("The ports have different addresses (" + String.join(", ", servers) + "); one connection gives one base URL,"
                    + " prefilled from the first operation chosen");
        }
        if (out.isEmpty()) {
            warnings.add("The WSDL has no service port with operations");
        }
        warnings.addAll(0, sources.warnings());
        return new Result(out, List.copyOf(servers), List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private SoapOperation operation(Port port, Binding b, PortTypeOp op) {
        String id = port.name() + "/" + op.name();
        BindingOp bop = b.operations().get(op.name());
        String unsupported = null;
        if (b.soapVersion() == null) {
            unsupported = "Port " + port.name() + " has no SOAP binding (only SOAP 1.1 and 1.2 are supported)";
        } else if (bop == null) {
            unsupported = "The binding does not describe this operation";
        } else if ("rpc".equals(bop.style() == null ? b.style() : bop.style())) {
            unsupported = "RPC style is not supported yet, only document/literal";
        } else if ("encoded".equals(bop.inputUse()) || "encoded".equals(bop.outputUse())) {
            unsupported = "Encoded use is not supported, only document/literal";
        }
        List<Part> in = op.input() == null ? List.of() : messages.getOrDefault(op.input(), List.of());
        List<Part> outParts = op.output() == null ? List.of() : messages.getOrDefault(op.output(), List.of());
        if (unsupported == null && (in.size() > 1 || outParts.size() > 1)) {
            unsupported = "A message has more than one part; only one element per message is supported";
        }
        if (unsupported == null && (in.stream().anyMatch(p -> p.element() == null) || outParts.stream().anyMatch(p -> p.element() == null))) {
            unsupported = "A message part refers to a type, not an element; only document/literal with elements is supported";
        }
        String label = id;
        if (unsupported == null && bop.headers()) {
            warnings.add(label + ": SOAP headers are not mapped yet; the call is sent without them");
        }
        SoapTarget.Element body = null;
        PayloadDefinition.Field request = null;
        Map<String, String> ns = new LinkedHashMap<>();
        Map<String, List<String>> order = new LinkedHashMap<>();
        if (unsupported == null && !in.isEmpty()) {
            QName el = in.get(0).element();
            body = new SoapTarget.Element(el.getLocalPart(), el.getNamespaceURI());
            Walk walk = new Walk(label, el.getNamespaceURI(), ns, order, null);
            request = walk.top("body", el);
        }
        PayloadDefinition.Field response = null;
        String responseElement = null;
        List<String> lists = new ArrayList<>();
        if (unsupported == null && !outParts.isEmpty()) {
            QName el = outParts.get(0).element();
            responseElement = el.getLocalPart();
            response = new Walk(label, el.getNamespaceURI(), null, null, lists).top("response", el);
        }
        SoapTarget soap = new SoapTarget(b.soapVersion() == null ? "1.1" : b.soapVersion(), bop == null ? "" : bop.action(), body,
                port.address(), ns.isEmpty() && order.isEmpty() ? null : new SoapTarget.Request(ns.isEmpty() ? null : ns, order.isEmpty() ? null : order),
                responseElement == null ? null : new SoapTarget.Response(responseElement, lists.isEmpty() ? null : lists));
        return new SoapOperation(id, op.documentation(), request, response, soap, unsupported);
    }

    // ---- XSD to field trees

    /**
     * One walk over an element tree. For a request it records namespaces ({@code ns}) and sequence order
     * ({@code order}) per element path; for a response the list paths in link notation ({@code lists}).
     */
    private final class Walk {

        private final String label;
        private final String bodyNs;
        private final Map<String, String> ns;
        private final Map<String, List<String>> order;
        private final List<String> lists;
        private final Set<QName> typesOnPath = new HashSet<>();

        Walk(String label, String bodyNs, Map<String, String> ns, Map<String, List<String>> order, List<String> lists) {
            this.label = label;
            this.bodyNs = bodyNs == null ? "" : bodyNs;
            this.ns = ns;
            this.order = order;
            this.lists = lists;
        }

        /** The body element itself: a field named {@code name} with the element's content. */
        PayloadDefinition.Field top(String name, QName element) {
            XmlSchemaElement el = schemas.getElementByQName(element);
            if (el == null) {
                warnings.add(label + ": element " + element.getLocalPart() + " is not in the schemas; it is shown as text");
                return new PayloadDefinition.Field(null, name, "text", false, null, null, null, null);
            }
            // Request paths start at "body", as request links do; a response's paths only name places in warnings.
            return content(name, el, true, name, "", 0);
        }

        /**
         * The field for an element's type. {@code path}: the element path for request metadata; {@code link}: the
         * link path of the element in the answer ("" for the response element itself).
         */
        private PayloadDefinition.Field content(String name, XmlSchemaElement el, boolean required, String path, String link, int depth) {
            XmlSchemaType type = typeOf(el);
            if (type == null) {
                QName tn = el.getSchemaTypeName();
                if (tn != null && XSD_NS.equals(tn.getNamespaceURI())) {
                    return simple(name, tn.getLocalPart(), required);
                }
                if (tn != null) {
                    warnings.add(label + ": type " + tn.getLocalPart() + " of " + el.getName() + " is not in the schemas; it is shown as text");
                }
                return new PayloadDefinition.Field(null, name, "text", required, null, null, null, null);
            }
            if (type instanceof XmlSchemaSimpleType st) {
                return simple(name, builtin(st), required);
            }
            XmlSchemaComplexType ct = (XmlSchemaComplexType) type;
            QName key = ct.getQName();
            if (depth > MAX_DEPTH || (key != null && !typesOnPath.add(key))) {
                // A list item has no name of its own; the warning names the list.
                String shown = name != null ? name : path.substring(path.lastIndexOf('.') + 1);
                warnings.add(label + ": " + shown + " refers to itself; it is shown without its inner fields");
                return new PayloadDefinition.Field(null, name, "object", required, null, null, List.of(), null);
            }
            try {
                if (ct.getContentModel() != null && ct.getContentModel().getContent() instanceof XmlSchemaSimpleContentExtension sx) {
                    attributesWarning(name, sx.getAttributes().isEmpty());
                    return simple(name, builtinName(sx.getBaseTypeName()), required);
                }
                if (ct.getContentModel() != null && ct.getContentModel().getContent() instanceof XmlSchemaSimpleContentRestriction sr) {
                    return simple(name, builtinName(sr.getBaseTypeName()), required);
                }
                attributesWarning(name, ct.getAttributes().isEmpty());
                List<PayloadDefinition.Field> fields = new ArrayList<>();
                List<String> names = new ArrayList<>();
                children(ct, fields, names, path, link, depth);
                if (order != null && names.size() > 1) {
                    order.put(path, names);
                }
                return new PayloadDefinition.Field(null, name, "object", required, null, null, fields, null);
            } finally {
                if (key != null) {
                    typesOnPath.remove(key);
                }
            }
        }

        private void attributesWarning(String name, boolean none) {
            if (!none) {
                warnings.add(label + ": the attributes of " + name + " are not mapped yet");
            }
        }

        /** The child elements of a complex type, base type first for an extension. */
        private void children(XmlSchemaComplexType ct, List<PayloadDefinition.Field> fields, List<String> names, String path, String link, int depth) {
            if (ct.getContentModel() != null) {
                XmlSchemaContent c = ct.getContentModel().getContent();
                if (c instanceof XmlSchemaComplexContentExtension ext) {
                    XmlSchemaType base = schemas.getTypeByQName(ext.getBaseTypeName());
                    if (base instanceof XmlSchemaComplexType bct) {
                        children(bct, fields, names, path, link, depth + 1);
                    } else if (ext.getBaseTypeName() != null && !XSD_NS.equals(ext.getBaseTypeName().getNamespaceURI())) {
                        warnings.add(label + ": base type " + ext.getBaseTypeName().getLocalPart() + " is not in the schemas");
                    }
                    particle(ext.getParticle(), fields, names, path, link, depth);
                } else if (c instanceof XmlSchemaComplexContentRestriction res) {
                    particle(res.getParticle(), fields, names, path, link, depth);
                }
                return;
            }
            particle(ct.getParticle(), fields, names, path, link, depth);
        }

        private void particle(XmlSchemaParticle p, List<PayloadDefinition.Field> fields, List<String> names, String path, String link, int depth) {
            if (p == null) {
                return;
            }
            if (p.getMaxOccurs() > 1 && !(p instanceof XmlSchemaElement) && !(p instanceof XmlSchemaAny)) {
                warnings.add(label + ": a repeated group in " + path + " is shown as single fields");
            }
            switch (p) {
                case XmlSchemaElement el -> element(el, p.getMinOccurs(), fields, names, path, link, depth);
                case XmlSchemaSequence seq -> seq.getItems().forEach(i -> member(i, fields, names, path, link, depth));
                case XmlSchemaAll all -> all.getItems().forEach(i -> member(i, fields, names, path, link, depth));
                case XmlSchemaChoice ch -> {
                    if (!ch.getItems().isEmpty()) {
                        warnings.add(label + ": " + path + " has a choice of " + ch.getItems().size() + " options; the first one is shown");
                        member(ch.getItems().get(0), fields, names, path, link, depth);
                    }
                }
                case XmlSchemaGroupRef g -> particle(g.getParticle(), fields, names, path, link, depth);
                case XmlSchemaAny any -> warnings.add(label + ": " + path + " allows any element (xs:any); that part is not shown");
                default -> warnings.add(label + ": a " + p.getClass().getSimpleName() + " in " + path + " is not supported");
            }
        }

        private void member(Object item, List<PayloadDefinition.Field> fields, List<String> names, String path, String link, int depth) {
            if (item instanceof XmlSchemaParticle p) {
                particle(p, fields, names, path, link, depth);
            }
        }

        private void element(XmlSchemaElement decl, long minOccurs, List<PayloadDefinition.Field> fields, List<String> names, String path,
                String link, int depth) {
            XmlSchemaElement el = decl.isRef() && decl.getRef().getTarget() != null ? decl.getRef().getTarget() : decl;
            QName wire = decl.isRef() ? el.getQName() : decl.getWireName();
            String name = wire == null ? el.getName() : wire.getLocalPart();
            if (name == null) {
                return;
            }
            if (names.contains(name)) {
                warnings.add(label + ": " + path + " has two elements named " + name + "; only the first is shown");
                return;
            }
            names.add(name);
            String childPath = path + "." + name;
            if (ns != null) {
                String elNs = wire == null || wire.getNamespaceURI() == null ? "" : wire.getNamespaceURI();
                if (!elNs.equals(bodyNs)) {
                    ns.put(childPath, elNs);
                }
            }
            boolean required = minOccurs >= 1 && !el.isNillable() && !decl.isNillable();
            boolean list = decl.getMaxOccurs() > 1;
            String childLink = (link.isEmpty() ? "" : link + ".") + name + (list ? "[]" : "");
            if (list && lists != null) {
                lists.add(childLink);
            }
            PayloadDefinition.Field f = content(list ? null : name, el, list ? false : required, childPath, childLink, depth + 1);
            fields.add(list ? new PayloadDefinition.Field(null, name, "list", required, null, null, null, f) : f);
        }

        private XmlSchemaType typeOf(XmlSchemaElement el) {
            if (el.getSchemaType() != null) {
                return el.getSchemaType();
            }
            return el.getSchemaTypeName() == null ? null : schemas.getTypeByQName(el.getSchemaTypeName());
        }

        /** The XSD built-in a simple type restricts, following named restrictions; lists and unions are text. */
        private String builtin(XmlSchemaSimpleType st) {
            XmlSchemaSimpleType t = st;
            for (int i = 0; i < MAX_DEPTH && t != null; i++) {
                if (t.getQName() != null && XSD_NS.equals(t.getQName().getNamespaceURI())) {
                    return t.getQName().getLocalPart();
                }
                if (!(t.getContent() instanceof XmlSchemaSimpleTypeRestriction r)) {
                    return "string";
                }
                if (r.getBaseTypeName() != null && XSD_NS.equals(r.getBaseTypeName().getNamespaceURI())) {
                    return r.getBaseTypeName().getLocalPart();
                }
                t = r.getBaseType() != null ? r.getBaseType()
                        : r.getBaseTypeName() == null ? null : schemas.getTypeByQName(r.getBaseTypeName()) instanceof XmlSchemaSimpleType s ? s : null;
            }
            return "string";
        }

        private String builtinName(QName base) {
            if (base == null) {
                return "string";
            }
            if (XSD_NS.equals(base.getNamespaceURI())) {
                return base.getLocalPart();
            }
            return schemas.getTypeByQName(base) instanceof XmlSchemaSimpleType s ? builtin(s) : "string";
        }

        private PayloadDefinition.Field simple(String name, String xsdType, boolean required) {
            String type = switch (xsdType) {
                case "int", "integer", "long", "short", "byte", "nonNegativeInteger", "positiveInteger", "negativeInteger",
                        "nonPositiveInteger", "unsignedInt", "unsignedLong", "unsignedShort", "unsignedByte" -> "integer";
                case "decimal", "float", "double" -> "number";
                case "boolean" -> "boolean";
                case "date" -> "date";
                case "dateTime" -> "datetime";
                default -> "text";
            };
            String format = "base64Binary".equals(xsdType) ? "byte" : null;
            return new PayloadDefinition.Field(null, name, type, required, format, null, null, null);
        }
    }

    // ---- DOM helpers

    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element first(Element parent, String ns, String local) {
        List<Element> l = children(parent, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    /** A prefixed name in an attribute value, resolved against the element's namespace declarations; null if empty. */
    private static QName qname(Element context, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int colon = value.indexOf(':');
        String prefix = colon < 0 ? null : value.substring(0, colon);
        String local = colon < 0 ? value : value.substring(colon + 1);
        String uri = context.lookupNamespaceURI(prefix);
        return new QName(uri == null ? "" : uri, local);
    }
}
