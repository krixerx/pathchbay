package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SampleInferenceTest {

    @Test
    void infersTypesNestingAndListsAndFlagsGuesses() throws Exception {
        SampleInference.Result r = SampleInference.infer("person", "Person", Json.MAPPER.readTree("""
                {"name":"Mari","age":36,"score":4.5,"active":true,"born":"1990-02-01","seen":"2026-10-20T10:00:00Z",
                 "address":{"city":"Tallinn"},"tags":["a","b"],"orders":[{"no":"O1"},{"no":"O2","total":10}],
                 "nickname":null,"empty":[],"mixed":[1,"x"]}"""));

        PayloadDefinition d = r.definition();
        d.check(); // ids unique, types valid
        assertThat(type(d, "name")).isEqualTo("text");
        assertThat(type(d, "age")).isEqualTo("integer");
        assertThat(type(d, "score")).isEqualTo("number");
        assertThat(type(d, "active")).isEqualTo("boolean");
        assertThat(type(d, "born")).isEqualTo("date");
        assertThat(type(d, "seen")).isEqualTo("datetime");
        assertThat(type(d, "address")).isEqualTo("object");
        assertThat(field(d, "tags").items().type()).isEqualTo("text");
        assertThat(field(d, "orders").items().children()).extracting(PayloadDefinition.Field::name).containsExactly("no", "total");
        assertThat(d.find(field(d, "address").children().get(0).id()).orElseThrow().namePath()).containsExactly("address", "city");

        assertThat(r.guesses()).containsExactlyInAnyOrder(
                "nickname: the sample value is null; pick a type",
                "empty: the sample list is empty; pick the item type",
                "mixed: the sample list mixes item types; pick one");
    }

    @Test
    void sampleMustBeAnObject() {
        assertThatThrownBy(() -> SampleInference.infer("x", "X", Json.MAPPER.readTree("[1,2]")))
                .hasMessageContaining("JSON object");
    }

    static PayloadDefinition.Field field(PayloadDefinition d, String name) {
        return d.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    static String type(PayloadDefinition d, String name) {
        return field(d, name).type();
    }
}
