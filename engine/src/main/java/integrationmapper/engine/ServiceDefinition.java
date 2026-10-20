package integrationmapper.engine;

import java.util.List;

/**
 * One integration as stored in {@code config/services/<id>.json}. {@code input} and {@code output} name payload
 * definitions in the library (D-9). Skeleton subset of the plan's service file: no version history yet (E4).
 */
public record ServiceDefinition(String id, String version, String input, String output, Target target,
        Mapping request, Mapping response) {

    /** The external operation. {@code path} may contain {@code {name}} placeholders filled from the request script's {@code path} object. */
    public record Target(String baseUrl, String method, String path) {
    }

    public record Mapping(List<Link> links) {
    }

    /**
     * Request links: {@code from} is an Input field id, {@code to} a dotted path starting with path/query/headers/body.
     * Response links: {@code from} is a dotted path in the external response, {@code to} an Output field id.
     */
    public record Link(String from, String to) {
    }
}
