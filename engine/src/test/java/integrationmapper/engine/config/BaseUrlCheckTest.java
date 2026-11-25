package integrationmapper.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import integrationmapper.engine.format.Json;
import integrationmapper.engine.model.ServiceDefinition;

/**
 * Every base URL an integration calls passes the connection rule, wherever it comes from (harden-admin-surface): it
 * becomes a Camel endpoint, so a file: URI or a {{...}} placeholder must never get there. Valid literals stay allowed.
 */
class BaseUrlCheckTest {

    @TempDir
    Path dir;

    ServiceDefinition def(String target) throws Exception {
        return Json.MAPPER.readValue("""
                {"id":"get-book","input":"book-ref","output":"book-ref","target":%s,
                 "request":{"links":[]},"response":{"links":[]}}""".formatted(target), ServiceDefinition.class);
    }

    @Test
    void aLiteralBaseUrlThatIsNotHttpIsRefused() throws Exception {
        Connections connections = new Connections(new MockEnvironment(), new ConfigStore(dir.toString()));
        for (String url : new String[] {"file:/config/secrets", "bean:engine", "controlbus:route?action=stop", "http://api.example/{{env:X}}",
                "http://user:pw@api.example"}) {
            ServiceDefinition d = def("{\"baseUrl\":\"" + url + "\",\"method\":\"GET\",\"path\":\"/x\"}");
            assertThatThrownBy(() -> connections.resolve(d)).as(url).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Integration get-book's base URL is refused");
        }
    }

    @Test
    void aValidLiteralBaseUrlStillWorks() throws Exception {
        Connections connections = new Connections(new MockEnvironment(), new ConfigStore(dir.toString()));
        assertThat(connections.resolve(def("{\"baseUrl\":\"https://api.books.example\",\"method\":\"GET\",\"path\":\"/x\"}")))
                .isEqualTo("https://api.books.example");
    }

    @Test
    void aBadVariableValueIsRefusedNamingTheVariable() throws Exception {
        Connections connections = new Connections(new MockEnvironment().withProperty("MAPPER_URL_BOOKS", "http://{{env:INBOUND_API_KEY}}.example"),
                new ConfigStore(dir.toString()));
        ServiceDefinition d = def("{\"connection\":\"books\",\"method\":\"GET\",\"path\":\"/x\"}");
        assertThatThrownBy(() -> connections.resolve(d)).hasMessageContaining("connection books (MAPPER_URL_BOOKS) is refused")
                .hasMessageContaining("not a valid URL");
    }

    @Test
    void aBadConnectionFileIsRefused() throws Exception {
        Files.createDirectories(dir.resolve("connections"));
        Files.writeString(dir.resolve("connections/books.json"), "{\"baseUrl\":\"file:/tmp/x\"}");
        Connections connections = new Connections(new MockEnvironment(), new ConfigStore(dir.toString()));
        ServiceDefinition d = def("{\"connection\":\"books\",\"method\":\"GET\",\"path\":\"/x\"}");
        assertThatThrownBy(() -> connections.resolve(d)).hasMessageContaining("connection books is refused");
    }
}
