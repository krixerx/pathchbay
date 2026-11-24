package integrationmapper.engine.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An OpenAPI 3 contract, or a Swagger 2.0 one converted to OpenAPI 3 (D-5). Its original file is
 * {@code <id>.openapi.json|yaml}. Stored without {@code sourceKind}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenApiContract(String id, String name, String sourceFile, String sourceUrl, String importedAt, List<String> servers,
        List<RestOperation> operations, List<String> warnings) implements Contract {

    @Override
    public OpenApiContract withName(String name) {
        return new OpenApiContract(id, name, sourceFile, sourceUrl, importedAt, servers, operations, warnings);
    }
}
