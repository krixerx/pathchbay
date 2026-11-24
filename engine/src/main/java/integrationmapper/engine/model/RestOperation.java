package integrationmapper.engine.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An operation of an OpenAPI contract. {@code id} is the OpenAPI operationId, or "METHOD path" when there is none;
 * {@code responseStatus} is the first 2xx response, the one that is mapped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestOperation(String id, String method, String path, String summary, List<Parameter> parameters,
        PayloadDefinition.Field requestBody, String responseStatus, PayloadDefinition.Field response) implements Operation {

    /** A path, query or header parameter. */
    public record Parameter(String name, String in, boolean required, String type) {
    }
}
