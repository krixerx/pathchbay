package integrationmapper.engine.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * XML parsing for contracts and SOAP answers that never reads a DTD or an external entity (D-30). A document with a
 * DOCTYPE is refused outright. {@link Sources} decides which schema locations a WSDL may pull in: entries of the
 * uploaded ZIP, or URLs on the contract URL's scheme, host and port.
 */
public final class SafeXml {

    private SafeXml() {
    }

    public static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not allowed: " + systemId);
            });
            b.setErrorHandler(null);
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses XML text; a DOCTYPE, an external entity or broken XML throws IllegalArgumentException naming {@code what}. */
    static Document parse(String xml, String what) {
        try {
            return builder().parse(new InputSource(new StringReader(xml)));
        } catch (SAXException e) {
            throw new IllegalArgumentException(what + " is not valid XML: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(what + " could not be read: " + e.getMessage(), e);
        }
    }

    public static Document parse(byte[] xml, String what) {
        try {
            return builder().parse(new ByteArrayInputStream(xml));
        } catch (SAXException e) {
            throw new IllegalArgumentException(what + " is not valid XML: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException(what + " could not be read: " + e.getMessage(), e);
        }
    }

    /** A DOM node as text, without an XML declaration. */
    public static String toText(Node node) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The files a contract may read: either the entries of an uploaded ZIP (paths with forward slashes) or URLs
     * fetched through {@code fetch}, limited to the scheme, host and port of {@code base}. Every file it hands out is
     * remembered, so the import can store exactly what it used.
     */
    public static final class Sources {

        private final Map<String, byte[]> zip;
        private final URI base;
        private final Function<String, String> fetch;
        private final Map<String, byte[]> used = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        private Sources(Map<String, byte[]> zip, URI base, Function<String, String> fetch) {
            this.zip = zip;
            this.base = base;
            this.fetch = fetch;
        }

        public static Sources ofZip(Map<String, byte[]> entries) {
            return new Sources(entries, null, null);
        }

        public static Sources ofUrl(String url, Function<String, String> fetch) {
            return new Sources(null, URI.create(url), fetch);
        }

        /** Only the main file: every other location is refused with a warning. */
        public static Sources none() {
            return new Sources(Map.of(), null, null);
        }

        public boolean isZip() {
            return zip != null && base == null;
        }

        /**
         * Resolves {@code location} against {@code from} (a key returned earlier, or null for the main file). Returns
         * the resolved key, or null with a warning when it may not be read. A ZIP path that leaves the ZIP throws.
         */
        public String resolve(String location, String from) {
            if (location == null || location.isBlank()) {
                return null;
            }
            if (base == null) {
                URI loc;
                try {
                    loc = URI.create(location.replace('\\', '/'));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Schema location " + location + " is not a valid path");
                }
                if (loc.getScheme() != null) {
                    warnings.add("Schema " + location + " is outside the uploaded files and was not read");
                    return null;
                }
                String dir = from == null || !from.contains("/") ? "" : from.substring(0, from.lastIndexOf('/') + 1);
                String path = URI.create("x:/" + dir).resolve(loc.getPath()).normalize().getPath();
                if (path.startsWith("/..") || path.contains("/../")) {
                    throw new IllegalArgumentException("Schema location " + location + " leaves the uploaded ZIP");
                }
                String key = path.startsWith("/") ? path.substring(1) : path;
                if (zip == null || !zip.containsKey(key)) {
                    warnings.add("Schema " + location + " is not among the uploaded files");
                    return null;
                }
                return key;
            }
            URI resolved;
            try {
                resolved = (from == null ? base : URI.create(from)).resolve(location.strip());
            } catch (IllegalArgumentException e) {
                warnings.add("Schema location " + location + " is not a valid URL and was not read");
                return null;
            }
            if (!sameOrigin(resolved)) {
                warnings.add("Schema " + resolved + " is on another host than the contract and was not read");
                return null;
            }
            return resolved.toString();
        }

        private boolean sameOrigin(URI u) {
            return u.getScheme() != null && u.getScheme().equalsIgnoreCase(base.getScheme())
                    && u.getHost() != null && u.getHost().equalsIgnoreCase(base.getHost()) && port(u) == port(base);
        }

        private static int port(URI u) {
            if (u.getPort() >= 0) {
                return u.getPort();
            }
            return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }

        /** The bytes of a resolved key; null with a warning when a URL could not be fetched. */
        public byte[] read(String key) {
            if (used.containsKey(key)) {
                return used.get(key);
            }
            byte[] bytes;
            if (base == null) {
                bytes = zip.get(key);
            } else {
                try {
                    bytes = fetch.apply(key).getBytes(StandardCharsets.UTF_8);
                } catch (RuntimeException e) {
                    warnings.add("Schema " + key + " could not be fetched: " + e.getMessage());
                    return null;
                }
            }
            used.put(key, bytes);
            return bytes;
        }

        /** Records the main file under its key, so it is stored with the rest. */
        public void main(String key, byte[] bytes) {
            used.put(key, bytes);
        }

        public Map<String, byte[]> used() {
            return used;
        }

        public List<String> warnings() {
            return warnings;
        }
    }
}
