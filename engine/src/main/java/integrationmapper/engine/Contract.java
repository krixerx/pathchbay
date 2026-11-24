package integrationmapper.engine;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An imported contract as stored in {@code config/contracts/<id>.json}: the operations normalized into field trees
 * (spec FR-004). The original file sits next to it unchanged (FR-005): {@code <id>.openapi.json|yaml} for OpenAPI, or
 * for a WSDL (D-30) the folder {@code <id>.wsdl/} with the WSDL and every XSD it used, {@code sourceFile} naming the
 * WSDL inside it. {@code sourceKind} is {@code wsdl} for those and missing for OpenAPI. {@code files} maps each file
 * in the folder to where it came from (a URL, or its path in the uploaded ZIP).
 * {@code sourceUrl} is where the file was fetched from, null for an uploaded file; the edit form offers it again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Contract(String id, String name, String sourceFile, String sourceUrl, String importedAt, List<String> servers,
        List<Operation> operations, List<String> warnings, String sourceKind, Map<String, String> files) {

    public Contract(String id, String name, String sourceFile, String sourceUrl, String importedAt, List<String> servers,
            List<Operation> operations, List<String> warnings) {
        this(id, name, sourceFile, sourceUrl, importedAt, servers, operations, warnings, null, null);
    }

    static final String WSDL = "wsdl";

    boolean isWsdl() {
        return WSDL.equals(sourceKind);
    }

    /**
     * One callable operation. {@code id} is the OpenAPI operationId, or "METHOD path" when there is none; for SOAP
     * it is {@code <port>/<operation>}. Request and response bodies are field trees in the same shape as payload
     * definitions, without field ids. A SOAP operation has {@code protocol} {@code soap} and its {@code soap}
     * details; {@code unsupported} says why one can't be chosen (RPC style, encoded use, ...).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(String id, String method, String path, String summary, List<Parameter> parameters,
            PayloadDefinition.Field requestBody, String responseStatus, PayloadDefinition.Field response,
            String protocol, SoapTarget soap, String unsupported) {

        public Operation(String id, String method, String path, String summary, List<Parameter> parameters,
                PayloadDefinition.Field requestBody, String responseStatus, PayloadDefinition.Field response) {
            this(id, method, path, summary, parameters, requestBody, responseStatus, response, null, null, null);
        }

        static final String SOAP = "soap";

        boolean isSoap() {
            return SOAP.equals(protocol);
        }
    }

    /** A path, query or header parameter. */
    public record Parameter(String name, String in, boolean required, String type) {
    }
}
