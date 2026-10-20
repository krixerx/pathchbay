package spike.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.CamelContext;
import org.apache.camel.support.PluginHelper;
import org.apache.camel.support.ResourceHelper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Spike E1 questions (eng review R7):
 *  Q1 can a templated platform-http route with a NEW path be added after startup and answer?
 *  Q2 what do callers of that integration get while it is replaced, and is another integration untouched?
 *  Q3 do the YAML route-template / templated-route keys load at runtime?
 * Findings are printed with the prefix "SPIKE:".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutingSpikeTest {

    @Autowired
    CamelContext camel;

    @Autowired
    IntegrationGate gate;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    int post(String slug) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug))
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json")
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    String body(String slug) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    @Order(1)
    void q1_addRouteAfterStartup() throws Exception {
        assertThat(post("alpha")).as("before add").isNotEqualTo(200);

        camel.addRouteFromTemplate("int-alpha", "postTemplate", Map.of("slug", "alpha", "version", "1.0.0"));
        camel.addRouteFromTemplate("int-beta", "postTemplate", Map.of("slug", "beta", "version", "1.0.0"));

        int status = post("alpha");
        System.out.println("SPIKE: Q1 status after runtime add = " + status + ", body = " + body("alpha"));
        assertThat(status).isEqualTo(200);
        assertThat(post("beta")).isEqualTo(200);
    }

    @Test
    @Order(2)
    void q2_replaceUnderLoad() throws Exception {
        Map<Integer, AtomicInteger> alpha = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> beta = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);

        Thread[] load = new Thread[4];
        for (int i = 0; i < load.length; i++) {
            String slug = i % 2 == 0 ? "alpha" : "beta";
            Map<Integer, AtomicInteger> hist = i % 2 == 0 ? alpha : beta;
            load[i] = new Thread(() -> {
                while (running.get()) {
                    hist.computeIfAbsent(post(slug), k -> new AtomicInteger()).incrementAndGet();
                }
            });
            load[i].start();
        }

        Thread.sleep(500);
        long t0 = System.nanoTime();
        camel.getRouteController().stopRoute("int-alpha");
        camel.removeRoute("int-alpha");
        camel.addRouteFromTemplate("int-alpha", "postTemplate", Map.of("slug", "alpha", "version", "1.1.0"));
        long swapMs = (System.nanoTime() - t0) / 1_000_000;
        Thread.sleep(500);
        running.set(false);
        for (Thread t : load) {
            t.join();
        }

        System.out.println("SPIKE: Q2 swap took " + swapMs + " ms");
        System.out.println("SPIKE: Q2 alpha (replaced) status counts = " + new TreeMap<>(alpha));
        System.out.println("SPIKE: Q2 beta (untouched) status counts = " + new TreeMap<>(beta));
        System.out.println("SPIKE: Q2 alpha body after swap = " + body("alpha"));

        assertThat(beta.keySet()).as("other integration must see only 200").containsOnly(200);
        assertThat(body("alpha")).contains("1.1.0");
    }

    @Test
    @Order(4)
    void q2b_repeatedSwapsAndErrorShape() throws Exception {
        Map<Integer, AtomicInteger> alpha = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> beta = new ConcurrentHashMap<>();
        Map<Integer, String> sample = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);

        Thread[] load = new Thread[6];
        for (int i = 0; i < load.length; i++) {
            String slug = i % 2 == 0 ? "alpha" : "beta";
            Map<Integer, AtomicInteger> hist = i % 2 == 0 ? alpha : beta;
            load[i] = new Thread(() -> {
                while (running.get()) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug))
                                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                        hist.computeIfAbsent(r.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                        if (r.statusCode() != 200) {
                            sample.putIfAbsent(r.statusCode(), "headers=" + r.headers().map() + " body=" + r.body());
                        }
                    } catch (Exception e) {
                        hist.computeIfAbsent(-1, k -> new AtomicInteger()).incrementAndGet();
                        sample.putIfAbsent(-1, e.toString());
                    }
                }
            });
            load[i].start();
        }

        long maxSwapMs = 0;
        for (int n = 0; n < 20; n++) {
            Thread.sleep(100);
            long t0 = System.nanoTime();
            camel.getRouteController().stopRoute("int-alpha");
            camel.removeRoute("int-alpha");
            camel.addRouteFromTemplate("int-alpha", "postTemplate", Map.of("slug", "alpha", "version", "2." + n + ".0"));
            maxSwapMs = Math.max(maxSwapMs, (System.nanoTime() - t0) / 1_000_000);
        }
        Thread.sleep(300);
        running.set(false);
        for (Thread t : load) {
            t.join();
        }

        System.out.println("SPIKE: Q2b 20 swaps, max swap " + maxSwapMs + " ms");
        System.out.println("SPIKE: Q2b alpha (replaced) status counts = " + new TreeMap<>(alpha));
        System.out.println("SPIKE: Q2b beta (untouched) status counts = " + new TreeMap<>(beta));
        sample.forEach((k, v) -> System.out.println("SPIKE: Q2b sample " + k + " -> " + v));

        assertThat(beta.keySet()).as("other integration must see only 200").containsOnly(200);
    }

    @Test
    @Order(5)
    void q2c_gatedSwapsGiveOnlyJson503() throws Exception {
        Map<Integer, AtomicInteger> alpha = new ConcurrentHashMap<>();
        Map<Integer, AtomicInteger> beta = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> nonOkShapes = new ConcurrentHashMap<>();
        AtomicBoolean running = new AtomicBoolean(true);

        Thread[] load = new Thread[6];
        for (int i = 0; i < load.length; i++) {
            String slug = i % 2 == 0 ? "alpha" : "beta";
            Map<Integer, AtomicInteger> hist = i % 2 == 0 ? alpha : beta;
            load[i] = new Thread(() -> {
                while (running.get()) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/integrations/" + slug))
                                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                        hist.computeIfAbsent(r.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                        if (r.statusCode() != 200) {
                            String shape = r.statusCode() + " retryAfter=" + r.headers().firstValue("Retry-After").orElse("none")
                                    + " json=" + r.body().startsWith("{\"error\":\"integration-unavailable\"");
                            nonOkShapes.computeIfAbsent(shape, k -> new AtomicInteger()).incrementAndGet();
                        }
                    } catch (Exception e) {
                        hist.computeIfAbsent(-1, k -> new AtomicInteger()).incrementAndGet();
                    }
                }
            });
            load[i].start();
        }

        long maxSwapMs = 0;
        for (int n = 0; n < 20; n++) {
            Thread.sleep(100);
            String version = "3." + n + ".0";
            long t0 = System.nanoTime();
            gate.swap("alpha", () -> {
                camel.getRouteController().stopRoute("int-alpha");
                camel.removeRoute("int-alpha");
                camel.addRouteFromTemplate("int-alpha", "postTemplate", Map.of("slug", "alpha", "version", version));
            });
            maxSwapMs = Math.max(maxSwapMs, (System.nanoTime() - t0) / 1_000_000);
        }
        Thread.sleep(300);
        running.set(false);
        for (Thread t : load) {
            t.join();
        }

        System.out.println("SPIKE: Q2c gated, 20 swaps, max swap incl. drain " + maxSwapMs + " ms");
        System.out.println("SPIKE: Q2c alpha (replaced) status counts = " + new TreeMap<>(alpha));
        System.out.println("SPIKE: Q2c beta (untouched) status counts = " + new TreeMap<>(beta));
        System.out.println("SPIKE: Q2c alpha non-200 shapes = " + new TreeMap<>(nonOkShapes));

        assertThat(beta.keySet()).containsOnly(200);
        assertThat(alpha.keySet()).as("replaced integration: only 200 or gated 503").isSubsetOf(200, 503);
        assertThat(nonOkShapes.keySet()).allMatch(s -> s.equals("503 retryAfter=2 json=true"));
    }

    @Test
    @Order(3)
    void q3_yamlTemplateAtRuntime() throws Exception {
        String yaml = """
                - routeTemplate:
                    id: getTemplate
                    parameters:
                      - name: slug
                    from:
                      uri: "platform-http:/api/v1/integrations/{{slug}}"
                      parameters:
                        httpMethodRestrict: POST
                      steps:
                        - setBody:
                            constant: "{\\"slug\\":\\"{{slug}}\\",\\"from\\":\\"yaml\\"}"
                - templatedRoute:
                    routeTemplateRef: getTemplate
                    routeId: int-gamma
                    parameters:
                      - name: slug
                        value: gamma
                """;
        PluginHelper.getRoutesLoader(camel).loadRoutes(ResourceHelper.fromString("spike.camel.yaml", yaml));

        int status = post("gamma");
        System.out.println("SPIKE: Q3 YAML templated route status = " + status + ", body = " + body("gamma"));
        assertThat(status).isEqualTo(200);
    }
}
