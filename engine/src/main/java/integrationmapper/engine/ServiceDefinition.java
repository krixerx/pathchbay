package integrationmapper.engine;

import java.util.List;

/**
 * One integration as stored in {@code config/services/<id>.json}.
 * Skeleton subset of the plan's service file: no payload definitions, constants or versions history yet.
 */
public record ServiceDefinition(String id, String version, Target target, Mapping request, Mapping response) {

    /** The external operation. {@code path} may contain {@code {name}} placeholders filled from the request script's {@code path} object. */
    public record Target(String baseUrl, String method, String path) {
    }

    public record Mapping(List<Link> links) {
    }

    /** Copies the value at dotted path {@code from} to dotted path {@code to}. */
    public record Link(String from, String to) {
    }
}
