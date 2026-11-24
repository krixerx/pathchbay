package integrationmapper.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

/** A test call that gets no answer says why in plain words, never "did not answer: null". */
class TestRunnerNoAnswerTest {

    private static final URI URI_9999 = URI.create("http://mock:9999/edge/status");

    @Test
    void aRefusedConnectionNamesTheAddress() {
        // The JDK's HttpClient wraps the refusal in a ConnectException without a message.
        String text = TestRunner.noAnswer(new ConnectException(), URI_9999);
        assertThat(text).startsWith("Nothing answered at mock:9999: the connection was refused.").doesNotContain("null");
    }

    @Test
    void aTimeoutAnUnknownHostAndOtherFailuresAreTold() {
        assertThat(TestRunner.noAnswer(new HttpTimeoutException("request timed out"), URI_9999))
                .isEqualTo("The external system at mock:9999 did not answer in time.");
        assertThat(TestRunner.noAnswer(new IOException(new UnknownHostException("nohost")), URI.create("http://nohost/x")))
                .startsWith("The host nohost is not known.");
        assertThat(TestRunner.noAnswer(new IOException(), URI_9999)).isEqualTo("The external system at mock:9999 did not answer: IOException");
    }
}
