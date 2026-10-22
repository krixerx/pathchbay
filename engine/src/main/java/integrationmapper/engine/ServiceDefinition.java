package integrationmapper.engine;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One integration. In {@code config/services/<id>.json} it is the editable draft plus {@code activeVersion}, the
 * version that is live. Inside a version file it is the published copy, with {@code version} set.
 * {@code input} and {@code output} name payload definitions in the library (D-9).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
// Console-only fields: the display name and which contract operation the target came from; the engine runs on target.
@JsonIgnoreProperties({"name", "contract", "operationId"})
public record ServiceDefinition(String id, String version, String activeVersion, String input, String output,
        Target target, Auth auth, Mapping request, Mapping response, Formats formats) {

    public ServiceDefinition(String id, String version, String activeVersion, String input, String output, Target target,
            Auth auth, Mapping request, Mapping response) {
        this(id, version, activeVersion, input, output, target, auth, request, response, null);
    }

    /** The external operation. {@code path} may contain {@code {name}} placeholders filled from the request script's {@code path} object. */
    public record Target(String baseUrl, String method, String path) {
    }

    /**
     * How the external system is authenticated. {@code bearer}: {@code Authorization: Bearer <secret>}.
     * {@code apiKey}: the secret in header or query parameter {@code name}. The secret itself is never stored here
     * ({@link SecretResolver}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Auth(String type, String in, String name) {

        void check(String slug) {
            boolean ok = "bearer".equals(type)
                    || ("apiKey".equals(type) && Set.of("header", "query").contains(in) && name != null && !name.isBlank());
            if (!ok) {
                throw new IllegalArgumentException("Integration " + slug
                        + ": auth must be {\"type\":\"bearer\"} or {\"type\":\"apiKey\",\"in\":\"header|query\",\"name\":\"...\"}");
            }
        }
    }

    /**
     * Wire formats (D-24). {@code request}: how the body goes to the external system, {@code json} or {@code xml};
     * its answer is read by its Content-Type either way. {@code output}: what callers get back. Missing means json.
     * An XML document needs one root element: {@code requestRoot} and {@code outputRoot} name it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Formats(String request, String requestRoot, String output, String outputRoot) {

        static final Formats JSON = new Formats(null, null, null, null);

        boolean requestXml() {
            return "xml".equals(request);
        }

        boolean outputXml() {
            return "xml".equals(output);
        }

        void check(String slug) {
            for (String f : new String[] {request, output}) {
                if (f != null && !Set.of("json", "xml").contains(f)) {
                    throw new IllegalArgumentException("Integration " + slug + ": format must be json or xml, not " + f);
                }
            }
            if (requestXml()) {
                XmlFormat.checkRoot(requestRoot, "request root element");
            }
            if (outputXml()) {
                XmlFormat.checkRoot(outputRoot, "output root element");
            }
        }
    }

    /** The formats, with JSON for anything not set. */
    Formats wire() {
        return formats == null ? Formats.JSON : formats;
    }

    public record Mapping(List<Link> links) {
    }

    /**
     * Request links: {@code from} is an Input field id, {@code to} a dotted path starting with path/query/headers/body.
     * Response links: {@code from} is a dotted path in the external response, {@code to} an Output field id.
     */
    public record Link(String from, String to) {
    }

    ServiceDefinition withVersion(String v) {
        return new ServiceDefinition(id, v, null, input, output, target, auth, request, response, formats);
    }
}
