package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** JSON ↔ XML conventions at the edges of a call (D-24, TODO-T4). */
class XmlFormatTest {

    static JsonNode json(String s) throws Exception {
        return Json.MAPPER.readTree(s);
    }

    @Test
    void writesObjectsUnderTheNamedRootAndListsAsRepeatedElements() throws Exception {
        String xml = XmlFormat.write(json("{\"title\":\"T\",\"pages\":42,\"tags\":[\"a\",\"b\"],\"author\":{\"name\":\"N\"}}"), "Book");
        assertThat(xml).isEqualTo("<Book><title>T</title><pages>42</pages><tags>a</tags><tags>b</tags><author><name>N</name></author></Book>");
    }

    @Test
    void writesAListBodyAsItems() throws Exception {
        assertThat(XmlFormat.write(json("[1,2]"), "ids")).isEqualTo("<ids><item>1</item><item>2</item></ids>");
    }

    @Test
    void readsWithoutTheRootAndMakesLinkedListsListsEvenWithOneItem() throws Exception {
        String xml = "<Library><name>L</name><books><title>Only</title><authors>A</authors></books></Library>";
        JsonNode node = XmlFormat.read(xml, XmlFormat.listPaths(List.of("books[].title", "books[].authors[]")));
        assertThat(node).isEqualTo(json("{\"name\":\"L\",\"books\":[{\"title\":\"Only\",\"authors\":[\"A\"]}]}"));
    }

    @Test
    void readsAListAnswerFromInsideItsRoot() throws Exception {
        String one = "<Books><Book><id>1</id></Book></Books>";
        String two = "<Books><Book><id>1</id></Book><Book><id>2</id></Book></Books>";
        List<String> lists = XmlFormat.listPaths(List.of("[]", "[].id"));
        assertThat(XmlFormat.read(one, lists)).isEqualTo(json("[{\"id\":\"1\"}]"));
        assertThat(XmlFormat.read(two, lists)).isEqualTo(json("[{\"id\":\"1\"},{\"id\":\"2\"}]"));
        assertThat(XmlFormat.read("<Books/>", lists)).isEqualTo(json("[]"));
    }

    @Test
    void attributesReadAsFields() throws Exception {
        assertThat(XmlFormat.read("<Book id=\"7\"><title>T</title></Book>", List.of())).isEqualTo(json("{\"id\":\"7\",\"title\":\"T\"}"));
    }

    @Test
    void typesTextByTheDefinition() throws Exception {
        PayloadDefinition def = Json.MAPPER.readValue("""
                {"id":"d","fields":[
                  {"id":"a","name":"count","type":"integer"},
                  {"id":"b","name":"ok","type":"boolean"},
                  {"id":"c","name":"name","type":"text"},
                  {"id":"d","name":"ids","type":"list","items":{"type":"integer"}},
                  {"id":"e","name":"bad","type":"number"}]}""", PayloadDefinition.class);
        JsonNode typed = XmlFormat.typed(json("{\"count\":\"3\",\"ok\":\"true\",\"name\":\"007\",\"ids\":\"5\",\"bad\":\"x\"}"), def.fields());
        assertThat(typed.toString()).isEqualTo("{\"count\":3,\"ok\":true,\"name\":\"007\",\"ids\":[5],\"bad\":\"x\"}");
    }

    @Test
    void detectsXmlByContentTypeOrFirstCharacter() {
        assertThat(XmlFormat.isXml("application/xml;charset=UTF-8", "{}")).isTrue();
        assertThat(XmlFormat.isXml("text/xml", "")).isTrue();
        assertThat(XmlFormat.isXml("application/json", "<a/>")).isFalse();
        assertThat(XmlFormat.isXml(null, "  <a/>")).isTrue();
        assertThat(XmlFormat.isXml(null, "{}")).isFalse();
    }

    @Test
    void rootMustBeAnXmlName() {
        XmlFormat.checkRoot("Book_1", "root");
        assertThatThrownBy(() -> XmlFormat.checkRoot("1book", "root")).hasMessageContaining("XML name");
        assertThatThrownBy(() -> XmlFormat.checkRoot(null, "root")).hasMessageContaining("empty");
        assertThatThrownBy(() -> XmlFormat.checkRoot("xmlThing", "root")).hasMessageContaining("XML name");
    }
}
