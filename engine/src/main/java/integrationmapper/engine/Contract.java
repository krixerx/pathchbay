package integrationmapper.engine;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An imported contract as stored in {@code config/contracts/<id>.json}: the operations normalized into field trees
 * (spec FR-004). The original file sits next to it unchanged as {@code <id>.openapi.json|yaml} (FR-005).
 * {@code sourceUrl} is where the file was fetched from, null for an uploaded file; the edit form offers it again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Contract(String id, String name, String sourceFile, String sourceUrl, String importedAt, List<String> servers,
        List<Operation> operations, List<String> warnings) {

    /**
     * One callable operation. {@code id} is the OpenAPI operationId, or "METHOD path" when there is none. Request
     * and response bodies are field trees in the same shape as payload definitions, without field ids.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(String id, String method, String path, String summary, List<Parameter> parameters,
            PayloadDefinition.Field requestBody, String responseStatus, PayloadDefinition.Field response) {
    }

    /** A path, query or header parameter. */
    public record Parameter(String name, String in, boolean required, String type) {
    }
}
