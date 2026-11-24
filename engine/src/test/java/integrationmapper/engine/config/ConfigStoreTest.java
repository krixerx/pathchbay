package integrationmapper.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import integrationmapper.engine.model.Version;

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

    /** R4: a write that starts while another is still writing the same file never mixes the two contents. */
    @Test
    void twoWritesOfOneFileAtOnceLeaveOneWholeFile() throws Exception {
        ConfigStore store = new ConfigStore(dir.toString());
        Path target = store.service("book");
        String first = "{\"id\":\"book\",\"name\":\"" + "a".repeat(200) + "\"}";
        String second = "{\"id\":\"book\",\"name\":\"b\"}";
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread slow = new Thread(() -> {
            try {
                store.write(target, out -> {
                    out.write(first.substring(0, 100).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    firstStarted.countDown();
                    try {
                        secondDone.await();
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    out.write(first.substring(100).getBytes(StandardCharsets.UTF_8));
                });
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        slow.start();
        firstStarted.await();
        store.write(target, out -> out.write(second.getBytes(StandardCharsets.UTF_8)));
        secondDone.countDown();
        slow.join();

        assertThat(failed.get()).isNull();
        assertThat(Files.readString(target)).isIn(first, second);
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
