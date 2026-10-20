package integrationmapper.engine;

/**
 * The immutable file {@code services/<slug>.versions/<version>.json}. It is self-contained: copies of the Input and
 * Output definitions are inside, so later library or draft edits never change a published version, also after a
 * restart (D-9). No secrets are stored here.
 */
public record VersionSnapshot(String version, String publishedAt, ServiceDefinition service, PayloadDefinition input,
        PayloadDefinition output) {
}
