package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** "Fill from the API later": the prefilled definitions and links compile and carry data through unchanged. */
class PrefillTest {

    static PayloadDefinition.Field f(String name, String type, boolean required) {
        return new PayloadDefinition.Field(null, name, type, required, null, null, null, null);
    }

    static PayloadDefinition.Field object(String name, PayloadDefinition.Field... fields) {
        return new PayloadDefinition.Field(null, name, "object", false, null, null, List.of(fields), null);
    }

    static PayloadDefinition.Field list(String name, PayloadDefinition.Field items) {
        return new PayloadDefinition.Field(null, name, "list", false, null, null, null, items);
    }

    static JsonNode run(Contract.Operation op, String input, String response) throws Exception {
        Prefill.Result in = Prefill.input("t-input", "T input", op);
        Prefill.Result out = Prefill.output("t-output", "T output", op);
        in.definition().check();
        out.definition().check();
        ServiceDefinition def = new ServiceDefinition("t", "1.0.0", null, "t-input", "t-output",
                new ServiceDefinition.Target("http://x", op.method(), op.path()), null,
                new ServiceDefinition.Mapping(in.links()), new ServiceDefinition.Mapping(out.links()));
        CompiledIntegration ci = CompiledIntegration.compile(def, in.definition(), out.definition());
        JsonNode request = Json.MAPPER.readTree(ci.mapRequest(input));
        JsonNode output = Json.MAPPER.readTree(ci.mapResponse(response));
        return Json.MAPPER.createObjectNode().<com.fasterxml.jackson.databind.node.ObjectNode>set("request", request).set("output", output);
    }

    @Test
    void nestedListsInBodyAndResponseAreCopiedAndLinkedLevelByLevel() throws Exception {
        PayloadDefinition.Field order = object(null,
                f("customer", "text", true),
                list("lines", object(null, f("sku", "text", true), f("qty", "integer", false),
                        list("tags", f(null, "text", false)))),
                object("address", f("city", "text", false)));
        Contract.Operation op = new Contract.Operation("POST /orders/{shop}", "POST", "/orders/{shop}", null,
                List.of(new Contract.Parameter("shop", "path", true, "text"), new Contract.Parameter("dryRun", "query", false, "boolean")),
                order, "201", object(null, f("orderId", "integer", false), list("lines", object(null, f("sku", "text", false)))));

        JsonNode r = run(op, """
                {"shop":"s1","dryRun":true,"customer":"Ann","address":{"city":"Tartu"},
                 "lines":[{"sku":"A","qty":2,"tags":["x","y"]},{"sku":"B"}]}""",
                """
                {"orderId":7,"lines":[{"sku":"A"},{"sku":"B"}]}""");

        assertThat(r.at("/request/path/shop").asText()).isEqualTo("s1");
        assertThat(r.at("/request/query/dryRun").asBoolean()).isTrue();
        // Optional values the caller left out are not sent (ScriptGeneratorTest).
        assertThat(r.at("/request/body")).isEqualTo(Json.MAPPER.readTree("""
                {"customer":"Ann","lines":[{"sku":"A","qty":2,"tags":["x","y"]},{"sku":"B"}],
                 "address":{"city":"Tartu"}}"""));
        assertThat(r.at("/output")).isEqualTo(Json.MAPPER.readTree("{\"orderId\":7,\"lines\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}"));
    }

    @Test
    void parameterAndBodyFieldWithTheSameNameShareOneInputField() throws Exception {
        Contract.Operation op = new Contract.Operation("PUT /Books/{id}", "PUT", "/Books/{id}", null,
                List.of(new Contract.Parameter("id", "path", true, "integer")),
                object(null, f("id", "integer", false), f("title", "text", false)), "200", null);
        Prefill.Result in = Prefill.input("t-input", "T input", op);
        assertThat(in.definition().fields()).extracting(PayloadDefinition.Field::name).containsExactly("id", "title");
        assertThat(in.links()).extracting(ServiceDefinition.Link::to).containsExactly("path.id", "body.id", "body.title");

        Prefill.Result out = Prefill.output("t-output", "T output", op);
        assertThat(out.definition().fields()).isEmpty();
        assertThat(out.notes()).anyMatch(n -> n.contains("does not describe the response"));
    }

    @Test
    void aListResponseBecomesOneItemsField() throws Exception {
        Contract.Operation op = new Contract.Operation("GET /Books", "GET", "/Books", null, List.of(), null, "200",
                list(null, object(null, f("id", "integer", false), f("title", "text", false))));
        JsonNode r = run(op, "{}", "[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]");
        assertThat(r.at("/output").toString()).isEqualTo("{\"items\":[{\"id\":1,\"title\":\"A\"},{\"id\":2,\"title\":\"B\"}]}");
    }
}
