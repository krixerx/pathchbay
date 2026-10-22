package integrationmapper.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The last live calls of every integration, in memory only (D-22): what the caller sent, the request sent out, the
 * external answer and the mapped output, shaped like a Test drawer result. One log line per call, without payloads.
 * {@code MAPPER_CALL_LOG=off} keeps only the log line, for payloads with personal data.
 */
@Component
public class CallLog {

    static final int KEEP = 100;
    private static final Logger log = LoggerFactory.getLogger(CallLog.class);

    private final boolean enabled;
    private final Map<String, Deque<Call>> calls = new ConcurrentHashMap<>();

    public CallLog(@Value("${MAPPER_CALL_LOG:on}") String setting) {
        this.enabled = !"off".equalsIgnoreCase(setting.trim());
    }

    /** One call; {@code at} is ISO-8601, {@code stage} is where it stopped, as in the Test drawer ("done" when it went through). */
    public record Call(String at, String version, int status, long durationMs, String stage, List<String> problems,
            JsonNode input, Map<String, Object> request, Map<String, Object> response, JsonNode output) {
    }

    boolean enabled() {
        return enabled;
    }

    void add(String slug, String method, Call call) {
        log.info("Call {} {} {} -> {} ({}) {} ms", slug, call.version(), method, call.status(), call.stage(), call.durationMs());
        if (!enabled) {
            return;
        }
        Deque<Call> list = calls.computeIfAbsent(slug, s -> new ArrayDeque<>());
        synchronized (list) {
            list.addFirst(call);
            if (list.size() > KEEP) {
                list.removeLast();
            }
        }
    }

    /** Newest first. */
    List<Call> list(String slug) {
        Deque<Call> list = calls.get(slug);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    void clear(String slug) {
        calls.remove(slug);
    }
}
