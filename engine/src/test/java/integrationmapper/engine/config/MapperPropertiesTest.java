package integrationmapper.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The mapper.* settings bind from the environment variables the README documents, and bad values stop startup. */
class MapperPropertiesTest {

    static MapperProperties bind(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return Binder.get(environment).bindOrCreate("mapper", MapperProperties.class);
    }

    @Test
    void defaultsWithoutAnySetting() {
        MapperProperties p = bind(Map.of());
        assertThat(p).isEqualTo(new MapperProperties("config", "", 52_428_800, 10_485_760, 10_000));
    }

    @Test
    void theDocumentedEnvironmentVariablesStillWork() {
        MapperProperties p = bind(Map.of("MAPPER_CONFIGDIR", "/config", "MAPPER_IMPORT_DIR", "/import", "MAPPER_MAXBLOBBYTES", "2097152",
                "MAPPER_MAXBODYBYTES", "4096", "MAPPER_OUTBOUNDTIMEOUTMS", "2000"));
        assertThat(p).isEqualTo(new MapperProperties("/config", "/import", 2_097_152, 4096, 2000));
    }

    @Test
    void aBadValueStopsStartupNamingTheSetting() {
        assertThatThrownBy(() -> bind(Map.of("MAPPER_MAXBLOBBYTES", "-1"))).rootCause().hasMessageContaining("mapper.max-blob-bytes");
        assertThatThrownBy(() -> bind(Map.of("MAPPER_OUTBOUNDTIMEOUTMS", "0"))).rootCause().hasMessageContaining("mapper.outbound-timeout-ms");
        assertThatThrownBy(() -> bind(Map.of("MAPPER_MAXBODYBYTES", "10"))).rootCause().hasMessageContaining("mapper.max-body-bytes");
    }
}
