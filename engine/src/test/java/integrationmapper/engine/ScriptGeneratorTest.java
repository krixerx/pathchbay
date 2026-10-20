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
    void missingSourceGivesNullInsteadOfError() throws Exception {
        CompiledIntegration ci = compile(List.of(), List.of(link("a.b.c", "o-z")));
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"a\":{}}")).get("z").isNull()).isTrue();
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"a\":\"text\"}")).get("z").isNull()).isTrue();
    }

    @Test
    void fieldNamesWithQuotesAreEscaped() throws Exception {
        CompiledIntegration ci = compile(List.of(link("i-q", "body.ok")), List.of());
        assertThat(Json.MAPPER.readTree(ci.mapRequest("{\"we\\\"ird\":1}")).path("body").path("ok").asInt()).isEqualTo(1);
    }

    @Test
    void invalidLinksAreRejected() {
        assertThatThrownBy(() -> compile(List.of(link("i-id", "elsewhere.a")), List.of())).hasMessageContaining("inside one of");
        assertThatThrownBy(() -> compile(List.of(link("i-id", "body")), List.of())).hasMessageContaining("inside one of");
        assertThatThrownBy(() -> compile(List.of(link("nope", "body.a")), List.of())).hasMessageContaining("not a field of in");
        assertThatThrownBy(() -> compile(List.of(), List.of(link("a", "nope")))).hasMessageContaining("not a field of out");
        assertThatThrownBy(() -> compile(List.of(), List.of(link("a", "o-z"), link("b", "o-z")))).hasMessageContaining("another link already writes to");
    }
}
