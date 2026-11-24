package integrationmapper.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every {@code mapper.*} setting in one place, checked at startup: an invalid value stops the engine with a message
 * that names the setting, instead of refusing every file or call later. The names, and the environment variables
 * that set them ({@code MAPPER_CONFIGDIR}, {@code MAPPER_IMPORT_DIR}, {@code MAPPER_MAXBLOBBYTES},
 * {@code MAPPER_MAXBODYBYTES}, {@code MAPPER_OUTBOUNDTIMEOUTMS}), are the ones the README documents.
 *
 * @param configDir         the config folder (ADR-004)
 * @param importDir         a folder of packages imported at every start (D-31); empty for none
 * @param maxBlobBytes      the largest file a caller may send or an external system may answer (D-29)
 * @param maxBodyBytes      the largest request body other than files
 * @param outboundTimeoutMs the connection-pool, connect and response timeout of an external call; each applies on
 *                          its own, so a call can take up to three times this before it gets 504
 */
@ConfigurationProperties("mapper")
public record MapperProperties(
        @DefaultValue("config") String configDir,
        @DefaultValue("") String importDir,
        @DefaultValue("52428800") long maxBlobBytes,
        @DefaultValue("10485760") long maxBodyBytes,
        @DefaultValue("10000") long outboundTimeoutMs) {

    public MapperProperties {
        if (configDir == null || configDir.isBlank()) {
            throw new IllegalArgumentException("mapper.config-dir must name a folder");
        }
        importDir = importDir == null ? "" : importDir.strip();
        if (maxBlobBytes < 1) {
            throw new IllegalArgumentException("mapper.max-blob-bytes must be at least 1, not " + maxBlobBytes);
        }
        if (maxBodyBytes < 1024) {
            throw new IllegalArgumentException("mapper.max-body-bytes must be at least 1024, not " + maxBodyBytes);
        }
        if (outboundTimeoutMs < 1) {
            throw new IllegalArgumentException("mapper.outbound-timeout-ms must be at least 1, not " + outboundTimeoutMs);
        }
    }
}
