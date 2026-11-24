package integrationmapper.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationRegistryTest {

    /** R10: an exception without a message (an NPE) still marks the integration broken instead of stopping startup. */
    @Test
    void aFailureWithoutAMessageIsStillRecorded() {
        IntegrationRegistry registry = new IntegrationRegistry();
        registry.markBroken("book", null);
        assertThat(registry.brokenReasons()).containsEntry("book", "failed without a message");
    }
}
