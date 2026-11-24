package integrationmapper.engine.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * An imported contract as stored in {@code config/contracts/<id>.json}: the operations normalized into field trees
 * (spec FR-004). The original file sits next to it unchanged (FR-005). There is one record per kind of contract; a
 * kind's operations are all of one kind too. {@code sourceUrl} is where the file was fetched from, null for an
 * uploaded file; the edit form offers it again.
 *
 * <p>The stored JSON is the same as before the kinds had types of their own: {@code sourceKind} names the kind, and a
 * file without it is OpenAPI. The console, exported packages and older engines read these files, so a new kind must
 * keep that rule ({@code StoredContractJsonTest}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "sourceKind", visible = true,
        defaultImpl = OpenApiContract.class)
@JsonSubTypes({@JsonSubTypes.Type(value = WsdlContract.class, name = WsdlContract.KIND)})
public sealed interface Contract permits OpenApiContract, WsdlContract {

    String id();

    String name();

    String sourceFile();

    String sourceUrl();

    String importedAt();

    List<String> servers();

    List<? extends Operation> operations();

    List<String> warnings();

    /** The same contract under another display name. */
    Contract withName(String name);
}
