package integrationmapper.engine.config;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import integrationmapper.engine.format.Json;

/**
 * The drafts {@code services/<slug>.json}, read and written under one lock per integration. Every change to a draft
 * is a read-modify-write of the whole file, so without the lock a console autosave that read the file before a
 * publish could write the old {@code activeVersion} back after it. One editor at a time (R3) still holds; this only
 * keeps the console, the publisher and package import from overwriting each other's fields.
 *
 * <p>Hold the lock only around one read-modify-write of one draft: never across a route swap, a publisher call or
 * another draft's lock.
 */
@Component
public class Drafts {

    public static final String SLUG = "[a-z0-9]+(-[a-z0-9]+)*";

    private final ConfigStore store;
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Drafts(ConfigStore store) {
        this.store = store;
    }

    public boolean exists(String slug) {
        return slug != null && slug.matches(SLUG) && Files.isRegularFile(store.service(slug));
    }

    /** The draft as stored; throws {@code IllegalArgumentException} "No integration x" for an unknown or invalid slug. */
    public ObjectNode read(String slug) throws IOException {
        if (!exists(slug)) {
            throw new IllegalArgumentException("No integration " + slug);
        }
        JsonNode node = Json.MAPPER.readTree(store.service(slug).toFile());
        if (!(node instanceof ObjectNode draft)) {
            throw new IllegalArgumentException("Integration " + slug + ": the service file is not a JSON object");
        }
        return draft;
    }

    /** Writes a draft; the caller must hold {@link #lock(String)} for it. */
    public void write(String slug, ObjectNode draft) throws IOException {
        ReentrantLock lock = locks.get(slug);
        if (lock == null || !lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Draft " + slug + " written without its lock");
        }
        store.writeJson(store.service(slug), draft);
    }

    /** Locks one draft for a read-modify-write: {@code try (var l = drafts.lock(slug)) { read; change; write; }}. */
    public Lock lock(String slug) {
        if (slug == null || !slug.matches(SLUG)) {
            throw new IllegalArgumentException("No integration " + slug);
        }
        ReentrantLock lock = locks.computeIfAbsent(slug, k -> new ReentrantLock());
        lock.lock();
        return lock::unlock;
    }

    /** Reads, changes and writes one draft under its lock; returns the draft as written. */
    public ObjectNode edit(String slug, Editor editor) throws IOException {
        try (Lock l = lock(slug)) {
            ObjectNode draft = read(slug);
            editor.edit(draft);
            write(slug, draft);
            return draft;
        }
    }

    /** A held draft lock; closing it unlocks. */
    @FunctionalInterface
    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }

    @FunctionalInterface
    public interface Editor {
        void edit(ObjectNode draft) throws IOException;
    }
}
