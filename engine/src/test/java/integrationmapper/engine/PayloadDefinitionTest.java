package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class PayloadDefinitionTest {

    static PayloadDefinition parse(String json) throws Exception {
        return Json.MAPPER.readValue(json, PayloadDefinition.class);
    }

    static JsonNode json(String s) throws Exception {
        return Json.MAPPER.readTree(s);
    }

    static final String PERSON = """
            {"id":"person","fields":[
              {"id":"p-name","name":"name","type":"text","required":true},
              {"id":"p-born","name":"born","type":"date"},
              {"id":"p-score","name":"score","type":"number"},
              {"id":"p-active","name":"active","type":"boolean"},
              {"id":"p-address","name":"address","type":"object","fields":[
                {"id":"p-city","name":"city","type":"text","required":true}]},
              {"id":"p-tags","name":"tags","type":"list","items":{"type":"text"}},
              {"id":"p-country","name":"country","type":"text","required":true,"value":"EE"}]}""";

    @Test
    void structuralProblemsAreAllReported() throws Exception {
        PayloadDefinition bad = parse("""
                {"id":"bad","fields":[
                  {"id":"x","name":"a","type":"text"},
                  {"id":"x","name":"a","type":"colour"},
                  {"id":"y","name":"o","type":"object","value":"no"},
                  {"id":"z","name":"l","type":"list"}]}""");
        assertThatThrownBy(bad::check)
                .hasMessageContaining("field id x is used twice")
                .hasMessageContaining("two fields named a")
                .hasMessageContaining("unknown type colour")
                .hasMessageContaining("only simple fields can be constants")
                .hasMessageContaining("list has no valid item type");
    }

    @Test
    void findResolvesIdsToCurrentNamePaths() throws Exception {
        PayloadDefinition p = parse(PERSON);
        assertThat(p.find("p-city").orElseThrow().namePath()).containsExactly("address", "city");
        assertThat(p.find("missing")).isEmpty();
    }

    @Test
    void validationReportsEveryProblemAndSkipsConstants() throws Exception {
        List<String> problems = InputValidator.validate(json("""
                {"born":"20.10.2026","score":"high","active":"yes","address":{},"tags":["a",1]}"""), parse(PERSON));
        assertThat(problems).containsExactlyInAnyOrder(
                "name: required",
                "born: must be a date like 2026-10-20",
                "score: must be a number",
                "active: must be true or false",
                "address.city: required",
                "tags[1]: must be text");
    }

    @Test
    void validInputHasNoProblems() throws Exception {
        assertThat(InputValidator.validate(json("""
                {"name":"Mari","born":"1990-02-01","score":4.5,"active":true,"address":{"city":"Tallinn"},"tags":[]}"""),
                parse(PERSON))).isEmpty();
    }

    @Test
    void flatMeansSimpleFieldsOrListsOfSimpleValues() throws Exception {
        assertThat(parse(PERSON).isFlat()).isFalse();
        assertThat(parse("""
                {"id":"q","fields":[{"id":"a","name":"a","type":"integer"},
                  {"id":"b","name":"b","type":"list","items":{"type":"text"}}]}""").isFlat()).isTrue();
    }

    @Test
    void queryValuesAreCoercedToTheirTypes() throws Exception {
        PayloadDefinition q = parse("""
                {"id":"q","fields":[{"id":"a","name":"id","type":"integer"},{"id":"b","name":"on","type":"boolean"},
                  {"id":"c","name":"ids","type":"list","items":{"type":"integer"}},{"id":"d","name":"bad","type":"integer"}]}""");
        ObjectNode raw = (ObjectNode) json("{\"id\":\"7\",\"on\":\"true\",\"ids\":[\"1\",\"2\"],\"bad\":\"x\",\"extra\":\"y\"}");
        JsonNode out = InputValidator.coerceQuery(raw, q);
        assertThat(out.toString()).isEqualTo("{\"id\":7,\"on\":true,\"ids\":[1,2],\"bad\":\"x\"}");
        assertThat(InputValidator.validate(out, q)).containsExactly("bad: must be a whole number");
    }
}
