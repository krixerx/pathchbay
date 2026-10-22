package integrationmapper.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** D-22: the call log keeps the last calls per integration, newest first, and nothing when it is off. */
class CallLogTest {

    private static CallLog.Call call(int n) {
        return new CallLog.Call("2026-10-22T12:00:00Z", "1.0.0", 200, n, "done", List.of(), null, null, null, null);
    }

    @Test
    void keepsTheLastHundredNewestFirst() {
        CallLog log = new CallLog("on");
        for (int i = 1; i <= CallLog.KEEP + 5; i++) {
            log.add("a", "GET", call(i));
        }
        assertThat(log.list("a")).hasSize(CallLog.KEEP);
        assertThat(log.list("a").get(0).durationMs()).isEqualTo(CallLog.KEEP + 5);
        assertThat(log.list("b")).isEmpty();
        log.clear("a");
        assertThat(log.list("a")).isEmpty();
    }

    @Test
    void offKeepsNothing() {
        CallLog log = new CallLog("OFF");
        log.add("a", "GET", call(1));
        assertThat(log.enabled()).isFalse();
        assertThat(log.list("a")).isEmpty();
    }
}
