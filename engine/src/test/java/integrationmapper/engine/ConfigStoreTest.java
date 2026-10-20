package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {

    @TempDir
    Path dir;

    @Test
    void writeThatFailsHalfwayLeavesTheOldFileAndNoTempFile() throws Exception {
        ConfigStore store = new ConfigStore(dir.toString());
        Path target = store.service("book");
        store.writeJson(target, java.util.Map.of("id", "book", "activeVersion", "1.0.0"));
        String before = Files.readString(target);

        assertThatThrownBy(() -> store.write(target, out -> {
            out.write("{\"id\":\"book\",\"activeVer".getBytes(StandardCharsets.UTF_8));
            throw new IOException("disk gone");
        })).hasMessageContaining("disk gone");

        assertThat(Files.readString(target)).isEqualTo(before);
        try (var files = Files.list(target.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("book.json");
        }
    }

    @Test
    void versionsAreSortedNumerically() throws Exception {
        ConfigStore store = new ConfigStore(dir.toString());
        for (String v : new String[] {"1.10.0", "1.2.0", "1.9.0"}) {
            store.writeJson(store.version("book", v), java.util.Map.of("version", v));
        }
        assertThat(store.versions("book")).containsExactly("1.2.0", "1.9.0", "1.10.0");
        assertThat(Version.parse("1.10.0").nextMinor().toString()).isEqualTo("1.11.0");
    }
}
