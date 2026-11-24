package integrationmapper.engine.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import integrationmapper.engine.format.Json;
import integrationmapper.engine.model.PayloadDefinition;
import integrationmapper.engine.model.RestOperation;
import integrationmapper.engine.model.ServiceDefinition;

class ContractShapesTest {

    private static PayloadDefinition.Field field(String name, String type, List<PayloadDefinition.Field> fields,
            PayloadDefinition.Field items) {
        return new PayloadDefinition.Field(null, name, type, false, null, null, fields, items);
    }

    private static PayloadDefinition.Field field(String name, String type) {
        return field(name, type, null, null);
    }

    private static final RestOperation OP = new RestOperation("createOrder", "POST", "/orders", null,
            List.of(new RestOperation.Parameter("X-Tenant", "header", false, "text")),
            field("body", "object", List.of(
                    field("customer", "object", List.of(field("first", "text")), null),
                    field("items", "list", null, field(null, "object", List.of(field("sku", "text")), null)),
                    field("extra", "object")), null),
            "201",
            field("response", "object", List.of(field("orderId", "text"), field("owner", "object", List.of(field("id", "text")), null)), null));

    private static PayloadDefinition payload(String json) throws Exception {
        return Json.MAPPER.readValue(json, PayloadDefinition.class);
    }

    @Test
    void findsTheContractTypeOfRequestAndResponsePaths() {
        assertThat(ContractShapes.requestType(OP, "body.customer")).isEqualTo("object");
        assertThat(ContractShapes.requestType(OP, "body.customer.first")).isEqualTo("text");
        assertThat(ContractShapes.requestType(OP, "body.items[]")).isEqualTo("list");
        assertThat(ContractShapes.requestType(OP, "body.items[].sku")).isEqualTo("text");
        assertThat(ContractShapes.requestType(OP, "headers.x-tenant")).isEqualTo("text");
        assertThat(ContractShapes.responseType(OP, "owner")).isEqualTo("object");
        assertThat(ContractShapes.responseType(OP, "$body")).isNull();
    }

    @Test
    void anUndescribedPathHasNoType() {
        assertThat(ContractShapes.requestType(OP, "body.gone")).isNull();
        assertThat(ContractShapes.requestType(OP, "body.extra.anything")).isNull();
        assertThat(ContractShapes.requestType(OP, "body.customer[]")).isNull();
    }

    @Test
    void listsEveryLinkBetweenASingleValueAndAnObject() throws Exception {
        PayloadDefinition input = payload("""
                {"id":"in","name":"In","fields":[{"id":"i-first","name":"first","type":"text"},
                 {"id":"i-addr","name":"addr","type":"object","fields":[{"id":"i-city","name":"city","type":"text"}]}]}""");
        PayloadDefinition output = payload("""
                {"id":"out","name":"Out","fields":[{"id":"o-owner","name":"owner","type":"text"}]}""");
        ServiceDefinition def = Json.MAPPER.readValue("""
                {"id":"s","version":"1.0.0","input":"in","output":"out","target":{"method":"POST","path":"/orders"},
                 "request":{"links":[{"from":"i-first","to":"body.customer"},{"from":"i-first","to":"body.customer.first"},
                   {"from":"i-addr","to":"body.extra"},{"from":"i-first","to":"body.gone"}]},
                 "response":{"links":[{"from":"owner","to":"o-owner"}]}}""", ServiceDefinition.class);

        assertThat(ContractShapes.problems(def, input, output, OP)).containsExactly(
                "i-first → body.customer: " + LinkRules.Reason.SINGLE_TO_OBJECT.message,
                "owner → o-owner: " + LinkRules.Reason.OBJECT_TO_SINGLE.message);
        assertThat(ContractShapes.problems(def, input, output, null)).isEmpty();
    }
}
