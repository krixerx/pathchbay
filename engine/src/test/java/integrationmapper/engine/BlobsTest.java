package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

/** D-29: a file as JSON, {@code {contentType, fileName, size, data}}, as callers send it and as the log shows it. */
class BlobsTest {

    static final PayloadDefinition UPLOAD = ScriptGeneratorTest.parse("""
            {"id":"upload","fields":[{"id":"u-title","name":"title","type":"text"},{"id":"u-file","name":"file","type":"blob","required":true}]}""");

    static JsonNode json(String s) throws Exception {
        return Json.MAPPER.readTree(s);
    }

    @Test
    void aCallersFileIsCheckedAsAnObjectWithBase64Data() throws Exception {
        assertThat(InputValidator.validate(json("{\"file\":\"QUJD\"}"), UPLOAD))
                .containsExactly("file: must be a file {fileName, contentType, data}");
        assertThat(InputValidator.validate(json("{\"file\":{\"fileName\":\"a.pdf\"}}"), UPLOAD)).containsExactly("file.data: required");
        assertThat(InputValidator.validate(json("{\"file\":{\"data\":\"not base64 !!!\",\"fileName\":7,\"contentType\":true}}"), UPLOAD))
                .containsExactlyInAnyOrder("file.data: must be base64", "file.fileName: must be text", "file.contentType: must be text");
        assertThat(InputValidator.validate(json("{}"), UPLOAD)).containsExactly("file: required");
        assertThat(InputValidator.validate(json("{\"file\":{\"data\":\"QUJD\",\"size\":99,\"extra\":1}}"), UPLOAD)).isEmpty();
    }

    @Test
    void sizeIsWorkedOutAndTheTypeDefaults() throws Exception {
        ObjectNode input = (ObjectNode) json("{\"title\":\"T\",\"file\":{\"data\":\"QUJD\",\"size\":99}}");
        Blobs.complete(input, UPLOAD, 1000);
        assertThat(input.path("file").toString()).isEqualTo("{\"data\":\"QUJD\",\"size\":3,\"contentType\":\"application/octet-stream\"}");
    }

    @Test
    void aFileLargerThanTheLimitIsRefused() throws Exception {
        ObjectNode input = (ObjectNode) json("{\"file\":{\"data\":\"QUJDRA==\"}}");
        assertThatThrownBy(() -> Blobs.complete(input, UPLOAD, 3))
                .isInstanceOf(Blobs.TooLargeException.class)
                .hasMessageContaining("file is 4 bytes, more than the limit of 3 bytes");
    }

    @Test
    void theFileNameComesFromContentDisposition() {
        assertThat(Blobs.fileName("attachment; filename=\"a b.pdf\"")).isEqualTo("a b.pdf");
        assertThat(Blobs.fileName("inline; filename=plain.txt")).isEqualTo("plain.txt");
        assertThat(Blobs.fileName("attachment; filename=\"x.pdf\"; filename*=UTF-8''r%C3%A4%C3%A4k.pdf")).isEqualTo("rääk.pdf");
        assertThat(Blobs.fileName("attachment")).isNull();
        assertThat(Blobs.fileName(null)).isNull();
        assertThat(Blobs.contentDisposition("a \"b\".pdf")).isEqualTo("attachment; filename=\"a \\\"b\\\".pdf\"");
        assertThat(Blobs.contentDisposition("rääk.pdf")).isEqualTo("attachment; filename=\"r__k.pdf\"; filename*=UTF-8''r%C3%A4%C3%A4k.pdf");
    }

    @Test
    void theLogShowsTheSizeInsteadOfTheData() throws Exception {
        JsonNode logged = Blobs.forLog(json("{\"title\":\"T\",\"file\":{\"fileName\":\"a.pdf\",\"size\":3,\"data\":\"QUJD\"},"
                + "\"nested\":{\"doc\":{\"contentType\":\"x\",\"data\":\"QUJDRA==\"}},\"other\":{\"data\":\"kept\"}}"));
        assertThat(logged.toString()).isEqualTo("{\"title\":\"T\",\"file\":{\"fileName\":\"a.pdf\",\"size\":3,\"data\":\"<3 bytes, base64>\"},"
                + "\"nested\":{\"doc\":{\"contentType\":\"x\",\"data\":\"<4 bytes, base64>\"}},\"other\":{\"data\":\"kept\"}}");
    }

    @Test
    void aFileInTheOutputGetsItsSizeAndBrokenBase64FailsTheMapping() {
        PayloadDefinition file = ScriptGeneratorTest.parse("""
                {"id":"file","fields":[{"id":"f-content","name":"content","type":"blob"}]}""");
        CompiledIntegration ci = ScriptGeneratorTest.compile("GET", null, file, file, java.util.List.of(),
                java.util.List.of(ScriptGeneratorTest.link("contentBase64", "f-content.data"), ScriptGeneratorTest.link("fileName", "f-content.fileName")));
        assertThat(ci.mapResponse("{\"contentBase64\":\"QUJD\",\"fileName\":\"a.pdf\"}", "application/json"))
                .isEqualTo("{\"content\":{\"data\":\"QUJD\",\"fileName\":\"a.pdf\",\"size\":3,\"contentType\":\"application/octet-stream\"}}");
        assertThatThrownBy(() -> ci.mapResponse("{\"contentBase64\":\"!!\"}", "application/json"))
                .hasMessageContaining("content.data: must be base64");
    }

    @Test
    void theOpenApiSchemaOfAFileIsTheJsonObject() {
        assertThat(OpenApiController.schema(UPLOAD.fields().get(1)).toString()).isEqualTo("{\"type\":\"object\",\"properties\":{"
                + "\"fileName\":{\"type\":\"string\"},\"contentType\":{\"type\":\"string\"},"
                + "\"size\":{\"type\":\"integer\",\"readOnly\":true},\"data\":{\"type\":\"string\",\"format\":\"byte\"}},"
                + "\"required\":[\"data\"],\"description\":\"A file; data is base64\"}");
    }

    @Test
    void theTestDrawerMockOfAFileIsASmallFile() {
        assertThat(MockGenerator.example(UPLOAD.fields().get(1)).toString())
                .isEqualTo("{\"fileName\":\"file.txt\",\"contentType\":\"text/plain\",\"size\":7,\"data\":\"ZXhhbXBsZQ==\"}");
    }
}
