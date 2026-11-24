package integrationmapper.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import integrationmapper.engine.format.Json;

/**
 * Stored contract files must read and write back unchanged: the console, exported packages and older engines read
 * them. The fixtures were written by the importers before contracts had a type per kind; the comparison ignores only
 * line endings, which git may change on checkout.
 */
class StoredContractJsonTest {

    @ParameterizedTest
    @ValueSource(strings = {"openapi.json", "wsdl.json"})
    void aStoredContractReadsAndWritesBackUnchanged(String file) throws Exception {
        String stored;
        try (var in = StoredContractJsonTest.class.getResourceAsStream("/stored-contracts/" + file)) {
            stored = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        Contract contract = Json.MAPPER.readValue(stored, Contract.class);
        String written = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(contract).replace("\r\n", "\n");
        assertThat(written).isEqualTo(stored);
    }
}
