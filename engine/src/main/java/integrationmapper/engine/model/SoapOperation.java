package integrationmapper.engine.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * An operation of a WSDL contract (D-30). {@code id} is {@code <port>/<operation>}; {@code soap} is what a call needs
 * beyond the URL, copied into the integration's target when the operation is chosen. {@code unsupported} says why the
 * operation can't be chosen (RPC style, encoded use, ...).
 *
 * <p>Stored with the REST-shaped fields the console reads on every operation ({@code method} POST, an empty
 * {@code path} and {@code parameters}, {@code responseStatus} 200) and {@code "protocol": "soap"}; they are fixed, so
 * they are written but not read back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(value = {"method", "path", "parameters", "responseStatus", "protocol"}, allowGetters = true)
@JsonPropertyOrder({"id", "method", "path", "summary", "parameters", "requestBody", "responseStatus", "response", "protocol", "soap",
        "unsupported"})
public record SoapOperation(String id, String summary, PayloadDefinition.Field requestBody, PayloadDefinition.Field response,
        SoapTarget soap, String unsupported) implements Operation {

    public static final String PROTOCOL = "soap";
    /** Every SOAP call is a POST to the endpoint itself. */
    public static final String METHOD = "POST";
    /** The status a SOAP answer is mapped from; a fault comes on 500 or, from some services, on 200 (D-30). */
    public static final String RESPONSE_STATUS = "200";

    @JsonProperty("protocol")
    public String protocol() {
        return PROTOCOL;
    }

    @JsonProperty("method")
    public String method() {
        return METHOD;
    }

    @JsonProperty("path")
    public String path() {
        return "";
    }

    @JsonProperty("parameters")
    public List<RestOperation.Parameter> parameters() {
        return List.of();
    }

    @JsonProperty("responseStatus")
    public String responseStatus() {
        return RESPONSE_STATUS;
    }
}
