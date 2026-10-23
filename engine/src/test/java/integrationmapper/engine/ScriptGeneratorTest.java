package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptGeneratorTest {

    static final PayloadDefinition IN = parse("""
            {"id":"in","fields":[
              {"id":"i-a","name":"a","type":"object","fields":[{"id":"i-b","name":"b","type":"integer"}]},
              {"id":"i-id","name":"id","type":"text"},
              {"id":"i-q","name":"we\\"ird","type":"text"},
              {"id":"i-k","name":"kind","type":"text","value":"BOOK"},
              {"id":"i-n","name":"limit","type":"integer","value":10}]}""");
    static final PayloadDefinition OUT = parse("""
            {"id":"out","fields":[
              {"id":"o-x","name":"x","type":"object","fields":[{"id":"o-y","name":"y","type":"integer"}]},
              {"id":"o-z","name":"z","type":"text"}]}""");

    static PayloadDefinition parse(String json) {
        try {
            PayloadDefinition d = Json.MAPPER.readValue(json, PayloadDefinition.class);
            d.check();
            return d;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ServiceDefinition.Link link(String from, String to) {
        return new ServiceDefinition.Link(from, to);
    }

    static CompiledIntegration compile(List<ServiceDefinition.Link> request, List<ServiceDefinition.Link> response) {
        return CompiledIntegration.compile(new ServiceDefinition("t", "1.0.0", null, "in", "out",
                new ServiceDefinition.Target("http://x", "POST", "/"), null,
                new ServiceDefinition.Mapping(request), new ServiceDefinition.Mapping(response)), IN, OUT);
    }

    @Test
    void requestAlwaysHasFourPartsAndResolvesFieldIds() throws Exception {
        CompiledIntegration ci = compile(List.of(link("i-b", "body.x.y"), link("i-id", "path.id")), List.of());
        String out = ci.mapRequest("{\"a\":{\"b\":5},\"id\":\"7\"}");
        assertThat(Json.MAPPER.readTree(out)).isEqualTo(Json.MAPPER.readTree(
                "{\"path\":{\"id\":\"7\"},\"query\":{},\"headers\":{},\"body\":{\"x\":{\"y\":5}}}"));
    }

    @Test
    void constantsAreWrittenAsLiterals() throws Exception {
        CompiledIntegration ci = compile(List.of(link("i-k", "body.kind"), link("i-n", "query.limit")), List.of());
        String out = ci.mapRequest("{\"kind\":\"caller\"}");
        assertThat(Json.MAPPER.readTree(out).path("body").path("kind").asText()).isEqualTo("BOOK");
        assertThat(Json.MAPPER.readTree(out).path("query").path("limit").asInt()).isEqualTo(10);
    }

    @Test
    void responseTargetsAreOutputFieldIds() throws Exception {
        CompiledIntegration ci = compile(List.of(), List.of(link("deep.value", "o-y"), link("name", "o-z")));
        String out = ci.mapResponse("{\"deep\":{\"value\":3},\"name\":\"n\"}");
        assertThat(Json.MAPPER.readTree(out)).isEqualTo(Json.MAPPER.readTree("{\"x\":{\"y\":3},\"z\":\"n\"}"));
    }

    @Test
    void missingSourceLeavesTheKeyOutInsteadOfError() throws Exception {
        CompiledIntegration ci = compile(List.of(), List.of(link("a.b.c", "o-z")));
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"a\":{}}")).has("z")).isFalse();
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"a\":\"text\"}")).has("z")).isFalse();
    }

    @Test
    void explicitNullIsPassedOnButAMissingFieldIsLeftOut() throws Exception {
        CompiledIntegration ci = compile(List.of(), List.of(link("deep.value", "o-y"), link("name", "o-z")));
        // An explicit null resets the field; the missing "deep" leaves out "x" completely, not as {}.
        assertThat(ci.mapResponse("{\"name\":null}")).isEqualTo("{\"z\":null}");
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"deep\":{\"value\":null}}")).toString()).isEqualTo("{\"x\":{\"y\":null}}");
    }

    @Test
    void fieldNamesWithQuotesAreEscaped() throws Exception {
        CompiledIntegration ci = compile(List.of(link("i-q", "body.ok")), List.of());
        assertThat(Json.MAPPER.readTree(ci.mapRequest("{\"we\\\"ird\":1}")).path("body").path("ok").asInt()).isEqualTo(1);
    }

    @Test
    void invalidLinksAreRejected() {
        assertThatThrownBy(() -> compile(List.of(link("i-id", "elsewhere.a")), List.of())).hasMessageContaining("inside one of");
        assertThatThrownBy(() -> compile(List.of(link("i-id", "body")), List.of())).hasMessageContaining("Only a file (blob) can be the whole body");
        assertThatThrownBy(() -> compile(List.of(link("nope", "body.a")), List.of())).hasMessageContaining("not a field of in");
        assertThatThrownBy(() -> compile(List.of(), List.of(link("a", "nope")))).hasMessageContaining("not a field of out");
        assertThatThrownBy(() -> compile(List.of(), List.of(link("a", "o-z"), link("b", "o-z")))).hasMessageContaining("another link already writes to");
    }

    // ---- files (D-29): inside the scripts a blob is the JSON object {contentType, fileName, size, data}

    static final PayloadDefinition UPLOAD = parse("""
            {"id":"upload","fields":[{"id":"u-title","name":"title","type":"text"},{"id":"u-file","name":"file","type":"blob"}]}""");
    static final PayloadDefinition FILE = parse("""
            {"id":"file","fields":[{"id":"f-content","name":"content","type":"blob"}]}""");
    static final PayloadDefinition STORED = parse("""
            {"id":"stored","fields":[{"id":"s-name","name":"name","type":"text"},{"id":"s-file","name":"file","type":"blob"}]}""");
    static final String BLOB = "{\"fileName\":\"a.pdf\",\"contentType\":\"application/pdf\",\"data\":\"QUJD\"}";

    static CompiledIntegration compile(String method, ServiceDefinition.Formats formats, PayloadDefinition in, PayloadDefinition out,
            List<ServiceDefinition.Link> request, List<ServiceDefinition.Link> response) {
        return CompiledIntegration.compile(new ServiceDefinition("t", "1.0.0", null, in.id(), out.id(),
                new ServiceDefinition.Target("http://x", method, "/"), null,
                new ServiceDefinition.Mapping(request), new ServiceDefinition.Mapping(response), formats), in, out);
    }

    static ServiceDefinition.Formats formats(String request, String output) {
        return new ServiceDefinition.Formats(request, null, output, null);
    }

    @Test
    void aFileGoesAsAWholeOrInPartsIntoJson() throws Exception {
        CompiledIntegration parts = compile("POST", null, UPLOAD, STORED, List.of(link("u-file.data", "body.contentBase64"),
                link("u-file.fileName", "body.fileName"), link("u-title", "headers.X-Title")), List.of());
        assertThat(Json.MAPPER.readTree(parts.mapRequest("{\"title\":\"T\",\"file\":" + BLOB + "}")).path("body").toString())
                .isEqualTo("{\"contentBase64\":\"QUJD\",\"fileName\":\"a.pdf\"}");

        CompiledIntegration whole = compile("POST", null, UPLOAD, STORED, List.of(link("u-file", "body.document")), List.of());
        assertThat(Json.MAPPER.readTree(whole.mapRequest("{\"file\":" + BLOB + "}")).path("body").path("document"))
                .isEqualTo(Json.MAPPER.readTree(BLOB));
    }

    @Test
    void aFileIsTheWholeBodyOrTheWholeAnswer() throws Exception {
        CompiledIntegration raw = compile("PUT", formats("binary", null), UPLOAD, STORED, List.of(link("u-file", "body")), List.of());
        assertThat(Json.MAPPER.readTree(raw.mapRequest("{\"file\":" + BLOB + "}")).path("body")).isEqualTo(Json.MAPPER.readTree(BLOB));

        CompiledIntegration download = compile("GET", formats(null, "binary"), FILE, FILE, List.of(), List.of(link("$body", "f-content")));
        assertThat(Json.MAPPER.readTree(download.mapResponse("{\"$body\":" + BLOB + "}")).path("content"))
                .isEqualTo(Json.MAPPER.readTree(BLOB));

        CompiledIntegration name = compile("GET", null, FILE, STORED, List.of(), List.of(link("$body.fileName", "s-name")));
        assertThat(name.mapResponse("{\"$body\":" + BLOB + "}")).isEqualTo("{\"name\":\"a.pdf\"}");
    }

    @Test
    void aFileCanBeBuiltFromBase64Fields() throws Exception {
        CompiledIntegration ci = compile("GET", formats(null, "binary"), FILE, FILE, List.of(),
                List.of(link("contentBase64", "f-content.data"), link("fileName", "f-content.fileName")));
        assertThat(ci.mapResponse("{\"contentBase64\":\"QUJD\",\"fileName\":\"a.pdf\"}"))
                .isEqualTo("{\"content\":{\"data\":\"QUJD\",\"fileName\":\"a.pdf\"}}");
    }

    @Test
    void fileLinksThatCantWorkAreAllReported() {
        assertThatThrownBy(() -> compile("POST", null, UPLOAD, STORED,
                List.of(link("u-file", "body"), link("u-file", "headers.X-File"), link("u-file.colour", "body.c")),
                List.of(link("$body", "s-name"))))
                .hasMessageContaining("A file as the whole body needs the request format binary")
                .hasMessageContaining("A file can only go into the body")
                .hasMessageContaining("colour is not a part of a file (data, fileName, contentType, size)")
                .hasMessageContaining("The whole answer can only go into a file (blob)");
        assertThatThrownBy(() -> compile("POST", formats("multipart", null), UPLOAD, STORED,
                List.of(link("u-title", "body.meta.title")), List.of()))
                .hasMessageContaining("Multipart parts are single fields");
        assertThatThrownBy(() -> compile("GET", null, FILE, FILE, List.of(), List.of(link("fileName", "f-content.fileName"))))
                .hasMessageContaining("content: link the file's data (base64) too");
        assertThatThrownBy(() -> compile("GET", null, FILE, FILE, List.of(), List.of(link("data", "f-content.data"), link("n", "f-content.size"))))
                .hasMessageContaining("size is worked out from the data and can't be linked to");
        assertThatThrownBy(() -> compile("GET", null, FILE, STORED, List.of(), List.of(link("$body", "s-file"), link("a", "s-file"))))
                .hasMessageContaining("another link already writes to");
    }

    @Test
    void formatsMustFitTheMethodAndTheOutput() {
        assertThatThrownBy(() -> compile("GET", formats("binary", null), FILE, FILE, List.of(), List.of()))
                .hasMessageContaining("request format binary needs POST, PUT or PATCH");
        assertThatThrownBy(() -> compile("GET", formats(null, "binary"), FILE, STORED, List.of(), List.of(link("$body", "s-file"))))
                .hasMessageContaining("with output binary, the Output must be one linked file (blob)");
        assertThatThrownBy(() -> compile("GET", formats(null, "binary"), FILE, FILE, List.of(), List.of()))
                .hasMessageContaining("with output binary, the Output must be one linked file (blob)");
        assertThatThrownBy(() -> compile("POST", new ServiceDefinition.Formats("xml", "Doc", null, null), UPLOAD, STORED,
                List.of(link("u-title", "body.title")), List.of()))
                .hasMessageContaining("files (blobs) can't go into XML yet");
        assertThatThrownBy(() -> compile("POST", formats("pdf", null), UPLOAD, STORED, List.of(), List.of()))
                .hasMessageContaining("request format must be json, xml, binary or multipart, not pdf");
    }
}
