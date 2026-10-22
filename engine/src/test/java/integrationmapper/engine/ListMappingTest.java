package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** Lists in mappings (D-12), run through real DataSonnet. */
class ListMappingTest {

    static final PayloadDefinition PERSON = ScriptGeneratorTest.parse("""
            {"id":"person","fields":[
              {"id":"p-country","name":"country","type":"text"},
              {"id":"p-tags","name":"tags","type":"list","items":{"type":"text"}},
              {"id":"p-addresses","name":"addresses","type":"list","items":{"type":"object","fields":[
                {"id":"p-street","name":"street","type":"text"},
                {"id":"p-city","name":"city","type":"text"}]}},
              {"id":"p-orders","name":"orders","type":"list","items":{"type":"object","fields":[
                {"id":"p-order-no","name":"no","type":"text"},
                {"id":"p-lines","name":"lines","type":"list","items":{"type":"object","fields":[
                  {"id":"p-sku","name":"sku","type":"text"}]}}]}}]}""");

    static final PayloadDefinition BOOKS = ScriptGeneratorTest.parse("""
            {"id":"books","fields":[
              {"id":"b-books","name":"books","type":"list","items":{"type":"object","fields":[
                {"id":"b-id","name":"bookId","type":"integer"},
                {"id":"b-title","name":"title","type":"text"}]}}]}""");

    static ServiceDefinition.Link link(String from, String to) {
        return new ServiceDefinition.Link(from, to);
    }

    static JsonNode request(List<ServiceDefinition.Link> links, String input) throws Exception {
        CompiledIntegration ci = CompiledIntegration.compile(new ServiceDefinition("t", "1.0.0", null, "person", "books",
                new ServiceDefinition.Target("http://x", "POST", "/"), null, new ServiceDefinition.Mapping(links),
                new ServiceDefinition.Mapping(List.of())), PERSON, BOOKS);
        return Json.MAPPER.readTree(ci.mapRequest(input)).path("body");
    }

    static final String PERSON_JSON = """
            {"country":"EE","tags":["a","b"],
             "addresses":[{"street":"Pikk 1","city":"Tallinn"},{"street":"Ülikooli 2","city":"Tartu"}],
             "orders":[{"no":"O1","lines":[{"sku":"S1"},{"sku":"S2"}]},{"no":"O2","lines":[]}]}""";

    @Test
    void listItemsAreMappedOneByOneWithOuterFieldsRepeated() throws Exception {
        JsonNode body = request(List.of(
                link("p-addresses", "body.ADDRESSES[]"),
                link("p-street", "body.ADDRESSES[].line1"),
                link("p-city", "body.ADDRESSES[].town"),
                link("p-country", "body.ADDRESSES[].country")), PERSON_JSON);
        assertThat(body.toString()).isEqualTo("""
                {"ADDRESSES":[{"line1":"Pikk 1","town":"Tallinn","country":"EE"},\
                {"line1":"Ülikooli 2","town":"Tartu","country":"EE"}]}""");
    }

    @Test
    void nestedListsAreMappedLevelByLevel() throws Exception {
        JsonNode body = request(List.of(
                link("p-orders", "body.ORDERS[]"),
                link("p-lines", "body.ORDERS[].LINES[]"),
                link("p-sku", "body.ORDERS[].LINES[].SKU"),
                link("p-order-no", "body.ORDERS[].LINES[].ORDER")), PERSON_JSON);
        assertThat(body.toString()).isEqualTo("""
                {"ORDERS":[{"LINES":[{"SKU":"S1","ORDER":"O1"},{"SKU":"S2","ORDER":"O1"}]},{"LINES":[]}]}""");
    }

    @Test
    void listWithoutItemLinksIsCopiedAndMissingListIsLeftOut() throws Exception {
        List<ServiceDefinition.Link> links = List.of(link("p-tags", "body.TAGS[]"), link("p-addresses", "body.ADDRESSES[]"),
                link("p-street", "body.ADDRESSES[].line1"));
        assertThat(request(links, PERSON_JSON).path("TAGS").toString()).isEqualTo("[\"a\",\"b\"]");
        JsonNode empty = request(links, "{}");
        assertThat(empty.has("TAGS")).isFalse();
        assertThat(empty.has("ADDRESSES")).isFalse();
        JsonNode nulls = request(links, "{\"tags\":null,\"addresses\":null}");
        assertThat(nulls.toString()).isEqualTo("{\"TAGS\":null,\"ADDRESSES\":null}");
    }

    @Test
    void rootListOfTheResponseMapsIntoAnOutputList() throws Exception {
        CompiledIntegration ci = CompiledIntegration.compile(new ServiceDefinition("t", "1.0.0", null, "person", "books",
                new ServiceDefinition.Target("http://x", "GET", "/"), null, new ServiceDefinition.Mapping(List.of()),
                new ServiceDefinition.Mapping(List.of(link("[]", "b-books"), link("[].id", "b-id"), link("[].title", "b-title")))),
                PERSON, BOOKS);
        String out = ci.mapResponse("[{\"id\":1,\"title\":\"A\",\"pageCount\":3},{\"id\":2,\"title\":\"B\"}]");
        assertThat(out.replace(" ", "").replace("\n", ""))
                .isEqualTo("{\"books\":[{\"bookId\":1,\"title\":\"A\"},{\"bookId\":2,\"title\":\"B\"}]}");
    }

    @Test
    void everyProblemIsReportedAtOnceAndNothingCompiles() {
        assertThatThrownBy(() -> request(List.of(
                link("p-street", "body.ADDRESSES[].line1"),          // list not linked
                link("p-addresses", "body.ADDRESS"),                 // list to single
                link("p-country", "body.TAGS[]"),                    // single to list
                link("p-gone", "body.X")), "{}"))                   // broken: no such field
                .hasMessageContaining("p-gone is not a field of person")
                .hasMessageContaining("Link the surrounding list first")
                .hasMessageContaining("A list can only be linked to a list")
                .hasMessageContaining("A single value can't be linked to a list");
    }
}
