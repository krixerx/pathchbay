package integrationmapper.engine.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A WSDL 1.1 contract (D-30). Its original files are the folder {@code <id>.wsdl/} with the WSDL and every XSD it
 * used; {@code sourceFile} names the WSDL inside it, and {@code files} maps each file in the folder to where it came
 * from (a URL, or its path in the uploaded ZIP). Stored with {@code "sourceKind": "wsdl"}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(value = "sourceKind", allowGetters = true)
@JsonPropertyOrder({"id", "name", "sourceFile", "sourceUrl", "importedAt", "servers", "operations", "warnings", "sourceKind", "files"})
public record WsdlContract(String id, String name, String sourceFile, String sourceUrl, String importedAt, List<String> servers,
        List<SoapOperation> operations, List<String> warnings, Map<String, String> files) implements Contract {

    public static final String KIND = "wsdl";

    @JsonProperty("sourceKind")
    public String sourceKind() {
        return KIND;
    }

    @Override
    public WsdlContract withName(String name) {
        return new WsdlContract(id, name, sourceFile, sourceUrl, importedAt, servers, operations, warnings, files);
    }
}
