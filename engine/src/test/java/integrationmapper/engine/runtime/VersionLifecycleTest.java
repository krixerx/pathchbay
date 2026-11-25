package integrationmapper.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import integrationmapper.engine.testsupport.Admin;
import integrationmapper.engine.config.ConfigStore;
import integrationmapper.engine.config.Drafts;
import integrationmapper.engine.format.Json;
import integrationmapper.engine.format.Multipart;
import integrationmapper.engine.testsupport.FakeBooksApi;

/** E4: version files, rollback, restart, secrets and timeouts, against a real config folder. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class VersionLifecycleTest {

    static final Path CONFIG;
    static final int PORT;

    static {
        try (ServerSocket s = new ServerSocket(0)) {
            PORT = s.getLocalPort();
            CONFIG = Files.createTempDirectory("mapper-config");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("server.port", () -> PORT);
        r.add("mapper.config-dir", CONFIG::toString);
        r.add("mapper.outbound-timeout-ms", () -> 1000);
        r.add("mapper.max-blob-bytes", () -> 1000);
        r.add("mapper.max-body-bytes", () -> 20000);
        r.add("MAPPER_SECRET_SECURE_BOOK", () -> "tok-123");
        r.add("MAPPER_SECRET_KEYED_BOOK", () -> "key-456");
        r.add("MAPPER_SECRET_TENANT_BOOK", () -> "key-789");
    }

    @Autowired
    IntegrationPublisher publisher;

    @Autowired
    IntegrationRegistry registry;

    @Autowired
    ConfigLoader loader;

    @Autowired
    ConfigStore store;

    @Autowired
    Drafts drafts;

    @Autowired
    TestRunner tests;

    @Autowired
    integrationmapper.engine.web.IntegrationGate gate;

    @Autowired
    FakeBooksApi fake;

    @Autowired
    org.apache.camel.CamelContext camel;

    final HttpClient http = HttpClient.newHttpClient();

    static final String NEW_BOOK = """
            {"id":"new-book","fields":[
              {"id":"nb-title","name":"title","type":"text","required":true},
              {"id":"nb-pages","name":"pages","type":"integer"}]}""";
    static final String BOOK_REF = """
            {"id":"book-ref","fields":[{"id":"br-bookId","name":"bookId","type":"integer"},{"id":"br-title","name":"title","type":"text"}]}""";
    static final String NO_INPUT = """
            {"id":"no-input","fields":[]}""";

    static void write(String relative, String content) throws IOException {
        Path p = CONFIG.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    static String service(String slug, String method, String path, String requestTo, String auth) {
        return """
                {"id":"%s","input":"%s","output":"book-ref",
                 "target":{"baseUrl":"http://localhost:%d/fake","method":"%s","path":"%s"},%s
                 "request":{"links":[%s]},
                 "response":{"links":[{"from":"id","to":"br-bookId"},{"from":"title","to":"br-title"}]}}"""
                .formatted(slug, "POST".equals(method) ? "new-book" : "no-input", PORT, method, path,
                        auth == null ? "" : "\"auth\":" + auth + ",",
                        requestTo == null ? "" : "{\"from\":\"nb-title\",\"to\":\"" + requestTo + "\"}");
    }

    static {
        try {
            write("entities/new-book.json", NEW_BOOK);
            write("entities/book-ref.json", BOOK_REF);
            write("entities/no-input.json", NO_INPUT);
            write("entities/upload.json", """
                    {"id":"upload","fields":[{"id":"u-file","name":"file","type":"blob","required":true}]}""");
            write("entities/doc-content.json", """
                    {"id":"doc-content","fields":[{"id":"dc-id","name":"documentId","type":"text","required":true},
                      {"id":"dc-content","name":"content","type":"blob","required":true}]}""");
            write("entities/doc-upload.json", """
                    {"id":"doc-upload","fields":[{"id":"du-title","name":"title","type":"text"},{"id":"du-pages","name":"pages","type":"integer"},
                      {"id":"du-file","name":"file","type":"blob","required":true}]}""");
            write("entities/doc-id.json", """
                    {"id":"doc-id","fields":[{"id":"di-id","name":"documentId","type":"text","required":true}]}""");
            write("entities/doc-file.json", """
                    {"id":"doc-file","fields":[{"id":"df-content","name":"content","type":"blob"}]}""");
            write("entities/stored.json", """
                    {"id":"stored","fields":[{"id":"st-id","name":"documentId","type":"text"},{"id":"st-status","name":"status","type":"text"}]}""");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> call(String method, String slug, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/" + slug)).header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", "application/json");
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody()) : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode read(Path p) throws IOException {
        return Json.MAPPER.readTree(p.toFile());
    }

    @Test
    void publishWritesASelfContainedVersionAndRollbackReusesIt() throws Exception {
        write("services/draft-book.json", service("draft-book", "POST", "/api/v1/Books", "body.title", null));

        assertThat(publisher.publishDraft("draft-book")).isEqualTo("1.0.0");
        JsonNode v1 = read(store.version("draft-book", "1.0.0"));
        assertThat(v1.path("input").path("fields").toString()).contains("nb-title");
        assertThat(read(store.service("draft-book")).path("activeVersion").asText()).isEqualTo("1.0.0");
        call("POST", "draft-book", "{\"title\":\"T\"}");
        assertThat(Json.MAPPER.readTree((String) fake.lastRequest.get("body")).has("title")).isTrue();

        // Change the draft: the title now goes to description. Next publish is the next minor.
        write("services/draft-book.json", service("draft-book", "POST", "/api/v1/Books", "body.description", null));
        assertThat(publisher.publishDraft("draft-book")).isEqualTo("1.1.0");
        call("POST", "draft-book", "{\"title\":\"T\"}");
        assertThat(Json.MAPPER.readTree((String) fake.lastRequest.get("body")).has("description")).isTrue();

        // Rollback: 1.0.0 is live again, no new file.
        publisher.activateVersion("draft-book", "1.0.0");
        assertThat(store.versions("draft-book")).containsExactly("1.0.0", "1.1.0");
        assertThat(registry.activeVersions()).containsEntry("draft-book", "1.0.0");
        call("POST", "draft-book", "{\"title\":\"T\"}");
        assertThat(Json.MAPPER.readTree((String) fake.lastRequest.get("body")).has("title")).isTrue();
    }

    @Test
    void restartServesTheActiveVersionEvenAfterLibraryEdits() throws Exception {
        write("services/stable-book.json", service("stable-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("stable-book");

        // Rename "title" in the library, then reload everything as a restart would.
        write("entities/new-book.json", NEW_BOOK.replace("\"name\":\"title\"", "\"name\":\"heading\""));
        try {
            loader.loadAll();
            HttpResponse<String> kept = call("POST", "stable-book", "{\"title\":\"Kept\"}");
            assertThat(kept.statusCode()).as(kept.body()).isEqualTo(200);
            assertThat(Json.MAPPER.readTree((String) fake.lastRequest.get("body")).path("title").asText()).isEqualTo("Kept");
        } finally {
            write("entities/new-book.json", NEW_BOOK);
            loader.loadAll();
        }
    }

    @Test
    void brokenVersionFileIsSkippedAndOthersStillServe() throws Exception {
        write("services/ok-book.json", service("ok-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("ok-book");
        write("services/broken-book.json", service("broken-book", "POST", "/api/v1/Books", "body.title", null)
                .replaceFirst("\\{", "{\"activeVersion\":\"1.0.0\","));
        write("services/broken-book.versions/1.0.0.json", "{ not json");

        loader.loadAll();

        assertThat(registry.brokenReasons()).containsKey("broken-book");
        HttpResponse<String> ok = call("POST", "ok-book", "{\"title\":\"T\"}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        Files.delete(CONFIG.resolve("services/broken-book.json"));
    }

    @Test
    void failedPublishChangesNoFiles() throws Exception {
        write("services/careful-book.json", service("careful-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("careful-book");
        String serviceBefore = Files.readString(store.service("careful-book"));

        // A draft edit keeps activeVersion, as the console will; only publish/activate change it.
        write("services/careful-book.json", service("careful-book", "POST", "/api/v1/Books", "nowhere.title", null)
                .replaceFirst("\\{", "{\"activeVersion\":\"1.0.0\","));
        assertThatThrownBy(() -> publisher.publishDraft("careful-book")).hasMessageContaining("inside one of");

        assertThat(store.versions("careful-book")).containsExactly("1.0.0");
        assertThat(read(store.service("careful-book")).path("activeVersion").asText()).isEqualTo("1.0.0");
        assertThat(Files.readString(store.service("careful-book"))).isNotEqualTo(serviceBefore); // the draft edit itself stays
        assertThat(registry.activeVersions()).containsEntry("careful-book", "1.0.0");
    }

    /**
     * A base URL with a query of its own (SOAP and some REST services need one, e.g. ?sap-client=100): the path goes
     * before it and the query reaches the external system next to the auth query, live and in the Test drawer.
     */
    @Test
    void aQueryInTheBaseUrlIsKeptAndThePathGoesBeforeIt() throws Exception {
        write("services/tenant-book.json", service("tenant-book", "GET", "/secure", null,
                "{\"type\":\"apiKey\",\"in\":\"query\",\"name\":\"api_key\"}").replace("/fake\"", "/fake?tenant=t1\""));
        publisher.publishDraft("tenant-book");

        HttpResponse<String> live = call("GET", "tenant-book", null);
        assertThat(live.statusCode()).as(live.body()).isEqualTo(200);
        assertThat(fake.lastRequest.get("query")).isEqualTo("tenant=t1&api_key=key-789");

        fake.lastRequest.clear();
        TestRunner.Result drawer = tests.run("tenant-book", Json.MAPPER.createObjectNode(), true);
        assertThat(drawer.problems()).isEmpty();
        assertThat(fake.lastRequest.get("query")).isEqualTo("tenant=t1&api_key=key-789");
        assertThat(drawer.request().get("url").toString()).endsWith("/fake/secure?tenant=t1&api_key=***");
    }

    @Test
    void secretsComeFromEnvOrFileAndAreNeverStored() throws Exception {
        write("services/secure-book.json", service("secure-book", "GET", "/secure", null, "{\"type\":\"bearer\"}"));
        publisher.publishDraft("secure-book");
        call("GET", "secure-book", null);
        assertThat(((HttpHeaders) fake.lastRequest.get("headers")).getFirst("Authorization")).isEqualTo("Bearer tok-123");

        write("services/keyed-book.json", service("keyed-book", "GET", "/secure", null,
                "{\"type\":\"apiKey\",\"in\":\"query\",\"name\":\"api_key\"}"));
        publisher.publishDraft("keyed-book");
        call("GET", "keyed-book", null);
        assertThat(fake.lastRequest.get("query")).isEqualTo("api_key=key-456");

        write("services/file-book.json", service("file-book", "GET", "/secure", null,
                "{\"type\":\"apiKey\",\"in\":\"header\",\"name\":\"X-Api-Key\"}"));
        write("secrets/file-book.json", "{\"value\":\"from-file\"}");
        publisher.publishDraft("file-book");
        call("GET", "file-book", null);
        assertThat(((HttpHeaders) fake.lastRequest.get("headers")).getFirst("X-Api-Key")).isEqualTo("from-file");

        for (String slug : new String[] {"secure-book", "keyed-book", "file-book"}) {
            String stored = Files.readString(store.version(slug, "1.0.0")) + Files.readString(store.service(slug));
            assertThat(stored).doesNotContain("tok-123").doesNotContain("key-456").doesNotContain("from-file");
        }

        write("services/nosecret-book.json", service("nosecret-book", "GET", "/secure", null, "{\"type\":\"bearer\"}"));
        assertThatThrownBy(() -> publisher.publishDraft("nosecret-book"))
                .hasMessageContaining("MAPPER_SECRET_NOSECRET_BOOK").hasMessageContaining("secrets");
        assertThat(store.versions("nosecret-book")).isEmpty();
    }

    /** NFR-001: calls waiting for a slow external system don't queue behind each other (they ran 8 at a time). */
    @Test
    void parallelSlowCallsAreNotServedInBatches() throws Exception {
        write("services/pause-book.json", service("pause-book", "GET", "/pause", null, null));
        publisher.publishDraft("pause-book");
        call("GET", "pause-book", null); // warm up

        int calls = 24;
        long t0 = System.nanoTime();
        List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> sent = new java.util.ArrayList<>();
        for (int i = 0; i < calls; i++) {
            sent.add(http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/pause-book")).header("Authorization", Admin.AUTHORIZATION).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (var f : sent) {
            HttpResponse<String> r = f.get();
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // Each external call takes 0.5 s; three batches of 8 would take 1.5 s.
        assertThat(ms).as("%d calls of 0.5 s at once", calls).isLessThan(1200);
    }

    /** R7: a call that is inside the route when a new version is published finishes on the version it started on. */
    @Test
    void aCallInFlightDuringPublishFinishesOnItsVersion() throws Exception {
        String v1 = service("drain-book", "GET", "/pause", null, null);
        write("services/drain-book.json", v1);
        publisher.publishDraft("drain-book");

        var inFlight = http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/drain-book")).header("Authorization", Admin.AUTHORIZATION).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(200); // the call now waits for the external system (0.5 s)
        assertThat(gate.inFlight("drain-book")).as("the gate counts the call until it ends, not until the filter returns").isEqualTo(1);
        var draft = (com.fasterxml.jackson.databind.node.ObjectNode) read(store.service("drain-book"));
        draft.putObject("response").set("links", Json.MAPPER.readTree("[{\"from\":\"id\",\"to\":\"br-title\"}]"));
        write("services/drain-book.json", draft.toString());
        long t0 = System.nanoTime();
        publisher.publishDraft("drain-book");
        long publishMs = (System.nanoTime() - t0) / 1_000_000;

        HttpResponse<String> r = inFlight.get();
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree(r.body()).has("bookId")).as("answered by 1.0.0: " + r.body()).isTrue();
        assertThat(publishMs).as("publish waited for the call").isGreaterThan(100);
        HttpResponse<String> after = call("GET", "drain-book", null);
        assertThat(Json.MAPPER.readTree(after.body()).has("title")).as("answered by 1.1.0: " + after.body()).isTrue();
        // The caller has the answer a moment before the async dispatch that sent it counts the call out.
        for (int i = 0; i < 100 && gate.inFlight("drain-book") > 0; i++) {
            Thread.sleep(10);
        }
        assertThat(gate.inFlight("drain-book")).isZero();

        // Calls to integrations nobody published leave nothing behind.
        call("GET", "never-published-" + System.nanoTime(), null);
        assertThat(gate.inFlight("never-published")).isZero();
    }

    @Test
    void slowRemoteTimesOutWith504() throws Exception {
        write("services/slow-book.json", service("slow-book", "GET", "/slow", null, null));
        publisher.publishDraft("slow-book");
        long t0 = System.nanoTime();
        HttpResponse<String> r = call("GET", "slow-book", null);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.statusCode()).isEqualTo(504);
        assertThat(r.body()).contains("remote-timeout");
        assertThat(ms).isLessThan(1500);
    }

    /** Runtime rule: an error of the external system passes through; the external system is called once, not retried. */
    @Test
    void remoteErrorIsPassedThroughWithoutARetry() throws Exception {
        write("services/busy-book.json", service("busy-book", "GET", "/busy", null, null));
        publisher.publishDraft("busy-book");
        fake.busyCalls.set(0);
        HttpResponse<String> r = call("GET", "busy-book", null);
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("X-Integration-Error")).contains("remote");
        assertThat(fake.busyCalls.get()).isEqualTo(1);
    }

    /** OV-8: the outbound request carries only mapped headers and auth, so a cookie the external system set never comes back. */
    @Test
    void cookiesFromTheRemoteAreNotSentOnLaterCalls() throws Exception {
        write("services/cookie-book.json", service("cookie-book", "GET", "/cookie", null, null));
        publisher.publishDraft("cookie-book");
        call("GET", "cookie-book", null);
        call("GET", "cookie-book", null);
        assertThat(((HttpHeaders) fake.lastRequest.get("headers")).get("Cookie")).isNull();
    }

    /** mapper.max-body-bytes limits a JSON body too, also when it is chunked and so declares no length. */
    @Test
    void aJsonBodyOverTheLimitIs413EvenWhenChunked() throws Exception {
        write("services/size-book.json", service("size-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("size-book");
        fake.lastRequest.clear();
        String big = "{\"title\":\"" + "x".repeat(30_000) + "\"}";
        URI uri = URI.create("http://localhost:" + PORT + "/api/v1/integrations/size-book");

        HttpResponse<String> declared = http.send(HttpRequest.newBuilder(uri).header("Authorization", Admin.AUTHORIZATION).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(big)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(declared.statusCode()).as(declared.body()).isEqualTo(413);
        assertThat(declared.body()).contains("payload-too-large").contains("more than the limit of 20000 bytes");

        HttpResponse<String> chunked = http.send(HttpRequest.newBuilder(uri).header("Authorization", Admin.AUTHORIZATION).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).as(chunked.body()).isEqualTo(413);
        assertThat(chunked.body()).contains("payload-too-large");
        assertThat(fake.lastRequest).as("nothing sent").isEmpty();

        HttpResponse<String> small = call("POST", "size-book", "{\"title\":\"T\"}");
        assertThat(small.statusCode()).as(small.body()).isEqualTo(200);
    }

    /** D-29: a file over mapper.max-blob-bytes is refused with 413 before anything is sent; one under it goes out as JSON. */
    @Test
    void aFileOverTheLimitIs413AndOneUnderItIsSent() throws Exception {
        write("services/upload-book.json", """
                {"id":"upload-book","input":"upload","output":"book-ref",
                 "target":{"baseUrl":"http://localhost:%d/fake","method":"POST","path":"/api/v1/Books"},
                 "request":{"links":[{"from":"u-file","to":"body.title"}]},
                 "response":{"links":[{"from":"id","to":"br-bookId"}]}}""".formatted(PORT));
        publisher.publishDraft("upload-book");
        fake.lastRequest.clear();

        String big = java.util.Base64.getEncoder().encodeToString(new byte[1001]);
        HttpResponse<String> tooLarge = call("POST", "upload-book", "{\"file\":{\"data\":\"" + big + "\"}}");
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(tooLarge.body()).contains("payload-too-large").contains("file is 1001 bytes, more than the limit of 1000 bytes");
        assertThat(fake.lastRequest).isEmpty();

        HttpResponse<String> ok = call("POST", "upload-book", "{\"file\":{\"fileName\":\"a.txt\",\"data\":\"QUJD\"}}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(Json.MAPPER.readTree((String) fake.lastRequest.get("body")).path("title").toString())
                .isEqualTo("{\"fileName\":\"a.txt\",\"data\":\"QUJD\",\"size\":3,\"contentType\":\"application/octet-stream\"}");

        // The call log (D-22) shows the file's size, never its data.
        String calls = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/admin/api/integrations/upload-book/calls")).header("Authorization", Admin.AUTHORIZATION)
                .build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(calls).contains("<3 bytes, base64>").doesNotContain("QUJD");
    }

    HttpResponse<String> callRaw(String method, String slugAndQuery, String contentType, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/" + slugAndQuery)).header("Authorization", Admin.AUTHORIZATION)
                .header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void publishFilePut() throws Exception {
        write("services/file-put.json", """
                {"id":"file-put","input":"doc-content","output":"stored",
                 "target":{"baseUrl":"http://localhost:%d/fake","method":"PUT","path":"/files/{id}"},
                 "formats":{"request":"binary"},
                 "request":{"links":[{"from":"dc-id","to":"path.id"},{"from":"dc-content","to":"body"}]},
                 "response":{"links":[{"from":"documentId","to":"st-id"},{"from":"status","to":"st-status"}]}}""".formatted(PORT));
    }

    /** D-29: a raw body from the caller and a JSON file both go to the external system as the raw body, byte for byte. */
    @Test
    void aFileGoesOutAsTheRawBodyWhetherItCameRawOrAsJson() throws Exception {
        publishFilePut();
        publisher.publishDraft("file-put");
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i; // every byte value, so nothing may pass through text
        }

        HttpResponse<String> raw = callRaw("PUT", "file-put?documentId=D-1", "application/pdf", bytes,
                "Content-Disposition", "attachment; filename=\"a.pdf\"");
        assertThat(raw.statusCode()).as(raw.body()).isEqualTo(200);
        assertThat(raw.body()).isEqualTo("{\"documentId\":\"D-1\",\"status\":\"STORED\"}");
        assertThat((byte[]) fake.lastRequest.get("bytes")).isEqualTo(bytes);
        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Content-Type")).isEqualTo("application/pdf");
        assertThat(sent.getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"a.pdf\"");

        fake.lastRequest.clear();
        String json = "{\"documentId\":\"D-2\",\"content\":{\"fileName\":\"b.bin\",\"data\":\""
                + java.util.Base64.getEncoder().encodeToString(bytes) + "\"}}";
        HttpResponse<String> fromJson = call("PUT", "file-put", json);
        assertThat(fromJson.statusCode()).as(fromJson.body()).isEqualTo(200);
        assertThat((byte[]) fake.lastRequest.get("bytes")).isEqualTo(bytes);
        sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(sent.getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"b.bin\"");
    }

    /** D-29: a raw body only goes where the Input has a file; a declared length over the limit is refused unread. */
    @Test
    void aRawBodyNeedsAFileInTheInputAndFitsTheLimit() throws Exception {
        write("services/plain-book.json", service("plain-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("plain-book");
        HttpResponse<String> noFile = callRaw("POST", "plain-book", "text/plain", "hello".getBytes());
        assertThat(noFile.statusCode()).isEqualTo(415);

        publishFilePut();
        publisher.publishDraft("file-put");
        fake.lastRequest.clear();
        HttpResponse<String> tooLarge = callRaw("PUT", "file-put?documentId=D-3", "application/octet-stream", new byte[1500]);
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(tooLarge.body()).contains("payload-too-large");
        assertThat(fake.lastRequest).isEmpty();
    }

    void publishFileGet(String slug, String path, String output, boolean rawOutput, String responseLinks) throws Exception {
        write("services/" + slug + ".json", """
                {"id":"%s","input":"doc-id","output":"%s",
                 "target":{"baseUrl":"http://localhost:%d/fake","method":"GET","path":"%s"},%s
                 "request":{"links":[{"from":"di-id","to":"path.id"}]},
                 "response":{"links":[%s]}}""".formatted(slug, output, PORT, path,
                rawOutput ? "\"formats\":{\"output\":\"binary\"}," : "", responseLinks));
        publisher.publishDraft(slug);
    }

    HttpResponse<byte[]> get(String slugAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/" + slugAndQuery)).header("Authorization", Admin.AUTHORIZATION).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** D-29: a file answer reaches the caller as raw bytes or as JSON, and base64 JSON can become raw bytes. */
    @Test
    void fileAnswersReachTheCallerRawOrAsJson() throws Exception {
        publishFileGet("file-get", "/files/{id}/content", "doc-file", true, "{\"from\":\"$body\",\"to\":\"df-content\"}");
        publishFileGet("file-get-json", "/files/{id}/content", "doc-file", false, "{\"from\":\"$body\",\"to\":\"df-content\"}");
        publishFileGet("file-get-base64", "/files/{id}/base64", "doc-file", true,
                "{\"from\":\"contentBase64\",\"to\":\"df-content.data\"},{\"from\":\"fileName\",\"to\":\"df-content.fileName\"},"
                        + "{\"from\":\"contentType\",\"to\":\"df-content.contentType\"}");

        HttpResponse<byte[]> raw = get("file-get?documentId=x");
        assertThat(raw.statusCode()).as(new String(raw.body())).isEqualTo(200);
        assertThat(raw.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/pdf"));
        assertThat(raw.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"a.pdf\"");
        assertThat(raw.body()).isEqualTo(FakeBooksApi.ALL_BYTES);

        HttpResponse<byte[]> json = get("file-get-json?documentId=x");
        JsonNode file = Json.MAPPER.readTree(json.body()).path("content");
        assertThat(file.path("fileName").asText()).isEqualTo("a.pdf");
        assertThat(file.path("contentType").asText()).isEqualTo("application/pdf");
        assertThat(file.path("size").asInt()).isEqualTo(256);
        assertThat(java.util.Base64.getDecoder().decode(file.path("data").asText())).isEqualTo(FakeBooksApi.ALL_BYTES);

        HttpResponse<byte[]> fromBase64 = get("file-get-base64?documentId=x");
        assertThat(fromBase64.statusCode()).as(new String(fromBase64.body())).isEqualTo(200);
        assertThat(fromBase64.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/pdf"));
        assertThat(fromBase64.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"b.pdf\"");
        assertThat(fromBase64.body()).isEqualTo(FakeBooksApi.ALL_BYTES);
    }

    /** D-29: what goes wrong with file answers, and that integrations without $body keep their old behavior. */
    @Test
    void fileAnswersThatDontFit() throws Exception {
        publishFileGet("file-get", "/files/{id}/content", "doc-file", true, "{\"from\":\"$body\",\"to\":\"df-content\"}");
        publishFileGet("file-get-plain", "/files/{id}/content", "stored", false, "{\"from\":\"documentId\",\"to\":\"st-id\"}");

        HttpResponse<byte[]> big = get("file-get?documentId=big");
        assertThat(big.statusCode()).isEqualTo(502);
        assertThat(new String(big.body())).contains("remote-failure").contains("1500 bytes, more than the limit of 1000 bytes");

        HttpResponse<byte[]> noFile = get("file-get?documentId=json");
        assertThat(noFile.statusCode()).isEqualTo(502);
        assertThat(new String(noFile.body())).contains("The answer had no file to return");

        HttpResponse<byte[]> missing = get("file-get?documentId=missing");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().firstValue("X-Integration-Error")).contains("remote");
        assertThat(missing.body()).isEqualTo(new byte[] {0, 1, 2, (byte) 255});

        HttpResponse<byte[]> plain = get("file-get-plain?documentId=x");
        assertThat(plain.statusCode()).isEqualTo(500);
        assertThat(new String(plain.body())).contains("mapping-error");
    }

    /** An unexpected failure goes to the log with its stack and the request id; the caller gets the id, not the cause. */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anUnexpectedFailureIsLoggedAndTheCallerGetsTheRequestId(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        publishFileGet("file-get-odd", "/files/{id}/content", "stored", false, "{\"from\":\"documentId\",\"to\":\"st-id\"}");

        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/file-get-odd?documentId=x")).header("Authorization", Admin.AUTHORIZATION)
                .header("X-Request-Id", "case-4711").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.headers().firstValue("X-Request-Id")).contains("case-4711");
        JsonNode body = Json.MAPPER.readTree(r.body());
        assertThat(body.path("error").asText()).isEqualTo("mapping-error");
        assertThat(body.path("requestId").asText()).isEqualTo("case-4711");
        assertThat(body.path("detail").asText()).startsWith("The integration failed inside Patchbay");
        assertThat(output.getOut()).contains("Call file-get-odd 1.0.0 failed: 500 mapping-error, request case-4711").contains("\tat ");
        // The call's own log line is written right after the answer went out.
        for (int i = 0; i < 100 && !output.getOut().contains("Call file-get-odd 1.0.0 GET -> 500"); i++) {
            Thread.sleep(10);
        }
        assertThat(output.getOut()).containsPattern("Call file-get-odd 1\\.0\\.0 GET -> 500 \\(\\w+\\) \\d+ ms, request case-4711");

        // An id that isn't a plain token is replaced, not echoed.
        HttpResponse<String> odd = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/nothing")).header("Authorization", Admin.AUTHORIZATION)
                .header("X-Request-Id", "a b<script>").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(odd.headers().firstValue("X-Request-Id")).hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f-]{36}"));
    }

    void publishUpload(String slug, String format, String requestLinks) throws Exception {
        write("services/" + slug + ".json", """
                {"id":"%s","input":"doc-upload","output":"stored",
                 "target":{"baseUrl":"http://localhost:%d/fake","method":"POST","path":"/upload"},%s
                 "request":{"links":[%s]},
                 "response":{"links":[{"from":"documentId","to":"st-id"},{"from":"status","to":"st-status"}]}}""".formatted(slug, PORT,
                format == null ? "" : "\"formats\":{\"request\":\"" + format + "\"},", requestLinks));
        publisher.publishDraft(slug);
    }

    static Multipart.Body form(String title, String pages, String fileField, String fileName, byte[] file) {
        com.fasterxml.jackson.databind.node.ObjectNode parts = Json.MAPPER.createObjectNode().put("title", title).put("pages", pages);
        parts.putObject(fileField).put("fileName", fileName).put("contentType", "application/pdf")
                .put("data", java.util.Base64.getEncoder().encodeToString(file));
        return Multipart.write(parts);
    }

    /** D-29: multipart from callers is read by Patchbay, and with request format multipart it goes out as multipart. */
    @Test
    void multipartGoesInAndOut() throws Exception {
        publishUpload("upload-mp", "multipart", "{\"from\":\"du-title\",\"to\":\"body.title\"},{\"from\":\"du-pages\",\"to\":\"body.pages\"},"
                + "{\"from\":\"du-file\",\"to\":\"body.document\"}");
        publishUpload("upload-json", null, "{\"from\":\"du-file.fileName\",\"to\":\"body.name\"},{\"from\":\"du-file.data\",\"to\":\"body.base64\"}");

        Multipart.Body in = form("Contract", "7", "file", "a.pdf", FakeBooksApi.ALL_BYTES);
        HttpResponse<String> r = callRaw("POST", "upload-mp", in.contentType(), in.bytes());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"documentId\":\"UP-1\",\"status\":\"STORED\"}");
        HttpHeaders sent = (HttpHeaders) fake.lastRequest.get("headers");
        List<Multipart.Part> parts = Multipart.read((byte[]) fake.lastRequest.get("bytes"), sent.getFirst("Content-Type"));
        assertThat(parts).extracting(Multipart.Part::name).containsExactly("title", "pages", "document");
        assertThat(new String(parts.get(0).bytes())).isEqualTo("Contract");
        assertThat(new String(parts.get(1).bytes())).isEqualTo("7");
        assertThat(parts.get(2).fileName()).isEqualTo("a.pdf");
        assertThat(parts.get(2).contentType()).isEqualTo("application/pdf");
        assertThat(parts.get(2).bytes()).isEqualTo(FakeBooksApi.ALL_BYTES);

        // JSON in, multipart out.
        String json = "{\"title\":\"From JSON\",\"file\":{\"fileName\":\"b.pdf\",\"data\":\""
                + java.util.Base64.getEncoder().encodeToString(FakeBooksApi.ALL_BYTES) + "\"}}";
        assertThat(call("POST", "upload-mp", json).statusCode()).isEqualTo(200);
        sent = (HttpHeaders) fake.lastRequest.get("headers");
        parts = Multipart.read((byte[]) fake.lastRequest.get("bytes"), sent.getFirst("Content-Type"));
        assertThat(parts).extracting(Multipart.Part::name).containsExactly("title", "document");
        assertThat(parts.get(1).fileName()).isEqualTo("b.pdf");
        assertThat(parts.get(1).bytes()).isEqualTo(FakeBooksApi.ALL_BYTES);

        // Multipart in, base64 JSON out.
        assertThat(callRaw("POST", "upload-json", in.contentType(), in.bytes()).statusCode()).isEqualTo(200);
        sent = (HttpHeaders) fake.lastRequest.get("headers");
        assertThat(sent.getFirst("Content-Type")).startsWith("application/json");
        JsonNode body = Json.MAPPER.readTree((byte[]) fake.lastRequest.get("bytes"));
        assertThat(body.path("name").asText()).isEqualTo("a.pdf");
        assertThat(java.util.Base64.getDecoder().decode(body.path("base64").asText())).isEqualTo(FakeBooksApi.ALL_BYTES);
    }

    /** D-29: what multipart can't do: a missing file, a wrong type, a broken body, an integration without files. */
    @Test
    void multipartThatDoesNotFit() throws Exception {
        publishUpload("upload-mp", "multipart", "{\"from\":\"du-title\",\"to\":\"body.title\"},{\"from\":\"du-file\",\"to\":\"body.document\"}");
        fake.lastRequest.clear();

        Multipart.Body noFile = form("T", "seven", "other", "x.pdf", new byte[] {1});
        HttpResponse<String> missing = callRaw("POST", "upload-mp", noFile.contentType(), noFile.bytes());
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("file: required").contains("pages: must be a whole number");

        HttpResponse<String> broken = callRaw("POST", "upload-mp", "multipart/form-data; boundary=abc", "not multipart".getBytes());
        assertThat(broken.statusCode()).isEqualTo(400);
        assertThat(broken.body()).contains("The multipart body could not be read");
        assertThat(fake.lastRequest).isEmpty();

        write("services/plain-book.json", service("plain-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("plain-book");
        Multipart.Body any = form("T", "1", "file", "x.pdf", new byte[] {1});
        assertThat(callRaw("POST", "plain-book", any.contentType(), any.bytes()).statusCode()).isEqualTo(415);
    }

    @Test
    void adminPublishAndVersionsOverHttp() throws Exception {
        write("services/admin-book.json", service("admin-book", "POST", "/api/v1/Books", "body.title", null));
        HttpResponse<String> pub = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + PORT + "/admin/integrations/admin-book/publish")).header("Authorization", Admin.AUTHORIZATION)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pub.statusCode()).isEqualTo(200);
        assertThat(pub.body()).contains("\"activeVersion\":\"1.0.0\"");

        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + PORT + "/admin/integrations/admin-book/versions/9.9.9/activate")).header("Authorization", Admin.AUTHORIZATION)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("has no version 9.9.9").contains("\"integration\":\"admin-book\"");

        // A stored version whose literal base URL is not http(s) never becomes a route (harden-admin-surface).
        String version = Files.readString(CONFIG.resolve("services/admin-book.versions/1.0.0.json"));
        write("services/admin-book.versions/2.0.0.json", version.replace("\"1.0.0\"", "\"2.0.0\"")
                .replace("http://localhost:" + PORT + "/fake", "file:/tmp/patchbay-probe"));
        HttpResponse<String> file = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + PORT + "/admin/integrations/admin-book/versions/2.0.0/activate")).header("Authorization", Admin.AUTHORIZATION)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(file.statusCode()).as(file.body()).isEqualTo(400);
        assertThat(file.body()).contains("base URL is refused");
        assertThat(registry.activeVersions()).containsEntry("admin-book", "1.0.0");
    }

    /** R9: a route that can't be added leaves the previous version serving and nothing changed on disk. */
    @Test
    void failedRouteSwapKeepsTheLiveVersionAndChangesNoFiles() throws Exception {
        write("services/swap-book.json", service("swap-book", "POST", "/api/v1/Books", "body.title", null));
        publisher.publishDraft("swap-book");

        // Compiling works, adding the new route fails inside Camel, once. (This used to be a literal base URL with an
        // unknown scheme; since harden-admin-surface every base URL must be http(s) before a route is built, and Camel
        // accepts every such address, so the failure is injected where Camel adds the route.)
        String draft = Files.readString(store.service("swap-book"));
        java.util.concurrent.atomic.AtomicBoolean failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        camel.addLifecycleStrategy(new org.apache.camel.support.LifecycleStrategySupport() {
            @Override
            public void onRoutesAdd(java.util.Collection<org.apache.camel.Route> routes) {
                if (routes.stream().anyMatch(r -> r.getRouteId().equals("int-swap-book")) && failOnce.getAndSet(false)) {
                    throw new IllegalStateException("route swap-book can't be added");
                }
            }
        });
        assertThatThrownBy(() -> publisher.publishDraft("swap-book")).hasMessageContaining("can't be added");
        assertThat(failOnce).as("the injected failure was hit").isFalse();

        assertThat(Files.readString(store.service("swap-book"))).as("activeVersion still 1.0.0").isEqualTo(draft);
        assertThat(store.versions("swap-book")).containsExactly("1.0.0");
        assertThat(registry.activeVersions()).containsEntry("swap-book", "1.0.0");
        assertThat(registry.brokenReasons()).doesNotContainKey("swap-book");
        HttpResponse<String> still = call("POST", "swap-book", "{\"title\":\"T\"}");
        assertThat(still.statusCode()).as(still.body()).isEqualTo(200);
    }

    /** A console save that holds the draft while a publish runs keeps both changes (the publish waits for it). */
    @Test
    void aDraftEditDuringPublishKeepsBothChanges() throws Exception {
        write("services/race-book.json", service("race-book", "POST", "/api/v1/Books", "body.title", null));
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        Thread publish = new Thread(() -> {
            try {
                publisher.publishDraft("race-book");
            } catch (Throwable t) {
                failed.set(t);
            }
        });

        drafts.edit("race-book", d -> {
            publish.start();
            // Without the lock the publish would write activeVersion now and this save would undo it.
            while (publish.isAlive() && publish.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
            d.put("name", "Renamed while publishing");
        });
        publish.join(10_000);

        assertThat(failed.get()).isNull();
        JsonNode saved = read(store.service("race-book"));
        assertThat(saved.path("name").asText()).isEqualTo("Renamed while publishing");
        assertThat(saved.path("activeVersion").asText()).isEqualTo("1.0.0");
    }
}
