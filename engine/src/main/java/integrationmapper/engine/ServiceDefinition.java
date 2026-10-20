package integrationmapper.engine;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One integration. In {@code config/services/<id>.json} it is the editable draft plus {@code activeVersion}, the
 * version that is live. Inside a version file it is the published copy, with {@code version} set.
 * {@code input} and {@code output} name payload definitions in the library (D-9).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceDefinition(String id, String version, String activeVersion, String input, String output,
        Target target, Auth auth, Mapping request, Mapping response) {

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

    public record Mapping(List<Link> links) {
    }

    /**
     * Request links: {@code from} is an Input field id, {@code to} a dotted path starting with path/query/headers/body.
     * Response links: {@code from} is a dotted path in the external response, {@code to} an Output field id.
     */
    public record Link(String from, String to) {
    }

    ServiceDefinition withVersion(String v) {
        return new ServiceDefinition(id, v, null, input, output, target, auth, request, response);
    }
}
