package integrationmapper.engine.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/** Safe parsing and schema locations for WSDL contracts (D-30, spec wsdl-contract-import). */
class SafeXmlTest {

    @TempDir
    Path dir;

    @Test
    void anExternalEntityIsRefusedAndTheFileIsNeverRead() throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><d>&x;</d>";

        assertThatThrownBy(() -> SafeXml.parse(xml, "The WSDL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The WSDL is not valid XML")
                .hasMessageNotContaining("TOP-SECRET-VALUE");
        assertThatThrownBy(() -> SafeXml.parse(xml.getBytes(StandardCharsets.UTF_8), "Schema a.xsd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZipPathThatLeavesTheZipIsRefused() {
        SafeXml.Sources s = SafeXml.Sources.ofZip(Map.of("main.wsdl", new byte[0], "x/a.xsd", new byte[0]));

        assertThat(s.resolve("x/a.xsd", "main.wsdl")).isEqualTo("x/a.xsd");
        assertThat(s.resolve("a.xsd", "x/b.xsd")).isEqualTo("x/a.xsd");
        assertThat(s.resolve("../a.xsd", "x/b.xsd")).isNull(); // main level, not in the ZIP: a warning
        assertThatThrownBy(() -> s.resolve("../../etc/schema.xsd", "x/b.xsd"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("../../etc/schema.xsd");
        assertThatThrownBy(() -> s.resolve("../schema.xsd", "main.wsdl")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZipNeverReadsFileOrHttpLocations() {
        SafeXml.Sources s = SafeXml.Sources.ofZip(Map.of("main.wsdl", new byte[0]));

        assertThat(s.resolve("file:///etc/passwd", "main.wsdl")).isNull();
        assertThat(s.resolve("http://elsewhere.example/a.xsd", "main.wsdl")).isNull();
        assertThat(s.warnings()).hasSize(2).allMatch(w -> w.contains("outside the uploaded files"));
    }

    @Test
    void aUrlContractReadsOnlyFromItsOwnSchemeHostAndPort() {
        List<String> fetched = new ArrayList<>();
        SafeXml.Sources s = SafeXml.Sources.ofUrl("http://host:8080/ws/person?wsdl", u -> {
            fetched.add(u);
            return "<x/>";
        });

        String xsd = s.resolve("http://host:8080/ws/person?xsd=1", null);
        assertThat(xsd).isEqualTo("http://host:8080/ws/person?xsd=1");
        assertThat(s.resolve("types.xsd", null)).isEqualTo("http://host:8080/ws/types.xsd");
        assertThat(s.resolve("http://other.example:8080/a.xsd", null)).isNull();
        assertThat(s.resolve("https://host:8080/a.xsd", null)).isNull();
        assertThat(s.resolve("http://host:9090/a.xsd", null)).isNull();
        assertThat(s.resolve("file:///etc/passwd", null)).isNull();
        assertThat(s.warnings()).hasSize(4).allMatch(w -> w.contains("another host"));

        assertThat(s.read(xsd)).isEqualTo("<x/>".getBytes(StandardCharsets.UTF_8));
        assertThat(fetched).containsExactly("http://host:8080/ws/person?xsd=1");
    }

    @Test
    void aSchemaThatCanNotBeFetchedIsAWarning() {
        SafeXml.Sources s = SafeXml.Sources.ofUrl("http://host/ws?wsdl", u -> {
            throw new IllegalArgumentException("The URL answered 404");
        });

        assertThat(s.read("http://host/ws?xsd=1")).isNull();
        assertThat(s.warnings()).singleElement().asString().contains("could not be fetched").contains("404");
    }
}
