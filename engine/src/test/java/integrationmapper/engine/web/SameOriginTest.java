package integrationmapper.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Which Origin counts as the console's own address (AdminRequestFilter.sameOrigin), also behind proxies. */
class SameOriginTest {

    /**
     * {@code serverName}/{@code serverPort} as Tomcat reports them: from Host, or from X-Forwarded-* behind a trusted
     * proxy. (The mock alone would derive them from the Host header.)
     */
    static MockHttpServletRequest request(String host, String serverName, int serverPort, String origin) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/admin/api/integrations") {
            @Override
            public String getServerName() {
                return serverName;
            }

            @Override
            public int getServerPort() {
                return serverPort;
            }
        };
        if (host != null) {
            r.addHeader("Host", host);
        }
        if (origin != null) {
            r.addHeader("Origin", origin);
        }
        return r;
    }

    @Test
    void directCallsAndScripts() {
        assertThat(AdminRequestFilter.sameOrigin(request("localhost:8080", "localhost", 8080, "http://localhost:8080"))).isTrue();
        assertThat(AdminRequestFilter.sameOrigin(request("localhost:8080", "localhost", 8080, null))).as("no Origin: a script").isTrue();
        assertThat(AdminRequestFilter.sameOrigin(request("localhost:8080", "localhost", 8080, "http://localhost:9090"))).isFalse();
        assertThat(AdminRequestFilter.sameOrigin(request("localhost:8080", "localhost", 8080, "http://evil.example"))).isFalse();
        assertThat(AdminRequestFilter.sameOrigin(request("localhost:8080", "localhost", 8080, "null"))).isFalse();
    }

    /** A proxy that terminates TLS and keeps Host: the engine sees port 80, the browser's Origin has 443. */
    @Test
    void aTlsProxyThatKeepsTheHostNeedsNoSetting() {
        assertThat(AdminRequestFilter.sameOrigin(request("patchbay.example", "patchbay.example", 80, "https://patchbay.example"))).isTrue();
        assertThat(AdminRequestFilter.sameOrigin(request("patchbay.example", "patchbay.example", 80, "https://evil.example"))).isFalse();
        assertThat(AdminRequestFilter.sameOrigin(request("Patchbay.Example:443", "patchbay.example", 443, "https://patchbay.example"))).isTrue();
    }

    /** A proxy that rewrites Host: only the forwarded address Spring resolved (serverName/serverPort) can match. */
    @Test
    void aProxyThatRewritesTheHostNeedsForwardedHeaders() {
        assertThat(AdminRequestFilter.sameOrigin(request("engine:8080", "engine", 8080, "https://patchbay.example"))).isFalse();
        assertThat(AdminRequestFilter.sameOrigin(request("engine:8080", "patchbay.example", 443, "https://patchbay.example"))).isTrue();
    }
}
