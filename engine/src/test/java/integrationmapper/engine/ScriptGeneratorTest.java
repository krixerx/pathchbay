package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScriptGeneratorTest {

    static ServiceDefinition.Link link(String from, String to) {
        return new ServiceDefinition.Link(from, to);
    }

    static CompiledIntegration compile(List<ServiceDefinition.Link> request, List<ServiceDefinition.Link> response) {
        return CompiledIntegration.compile(new ServiceDefinition("t", "1.0.0",
                new ServiceDefinition.Target("http://x", "POST", "/"),
                new ServiceDefinition.Mapping(request), new ServiceDefinition.Mapping(response)));
    }

    @Test
    void requestAlwaysHasFourPartsAndNestsTargets() throws Exception {
        CompiledIntegration ci = compile(List.of(link("a.b", "body.x.y"), link("id", "path.id")), List.of());
        String out = ci.mapRequest("{\"a\":{\"b\":5},\"id\":\"7\"}");
        assertThat(Json.MAPPER.readTree(out)).isEqualTo(Json.MAPPER.readTree(
                "{\"path\":{\"id\":\"7\"},\"query\":{},\"headers\":{},\"body\":{\"x\":{\"y\":5}}}"));
    }

    @Test
    void missingSourceGivesNullInsteadOfError() throws Exception {
        CompiledIntegration ci = compile(List.of(), List.of(link("a.b.c", "out")));
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"a\":{}}")).get("out").isNull()).isTrue();
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"a\":\"text\"}")).get("out").isNull()).isTrue();
    }

    @Test
    void fieldNamesWithQuotesAreEscaped() throws Exception {
        CompiledIntegration ci = compile(List.of(), List.of(link("we\"ird", "ok")));
        assertThat(Json.MAPPER.readTree(ci.mapResponse("{\"we\\\"ird\":1}")).get("ok").asInt()).isEqualTo(1);
    }

    @Test
    void invalidTargetsAreRejected() {
        assertThatThrownBy(() -> compile(List.of(link("a", "elsewhere.a")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile(List.of(), List.of(link("a", "x"), link("b", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile(List.of(), List.of(link("a", "x"), link("b", "x.y"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
