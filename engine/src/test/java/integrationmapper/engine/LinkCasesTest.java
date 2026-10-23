package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs every case of the shared {@code rules/link-cases.json} against {@link LinkRules} (R5). */
class LinkCasesTest {

    @TestFactory
    Stream<DynamicTest> sharedCases() throws Exception {
        JsonNode file = Json.MAPPER.readTree(Path.of("..", "rules", "link-cases.json").toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : file.get("cases")) {
            tests.add(DynamicTest.dynamicTest(c.get("name").asText(), () -> {
                List<LinkRules.ListLink> listLinks = new ArrayList<>();
                c.get("listLinks").forEach(l -> listLinks.add(
                        new LinkRules.ListLink(LinkRules.Path.of(l.get(0).asText()), LinkRules.Path.of(l.get(1).asText()))));
                String expected = c.get("reason").isNull() ? null : c.get("reason").asText();
                String actual = LinkRules.check(LinkRules.Path.of(c.get("source").asText()), c.path("sourceType").asText(null),
                        LinkRules.Path.of(c.get("target").asText()), c.path("targetType").asText(null),
                        c.path("requestFormat").asText(null), listLinks).map(r -> r.code).orElse(null);
                assertThat(actual).isEqualTo(expected);
            }));
        }
        return tests.stream();
    }
}
