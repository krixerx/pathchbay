package integrationmapper.engine;

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

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
        r.add("MAPPER_SECRET_SECURE_BOOK", () -> "tok-123");
        r.add("MAPPER_SECRET_KEYED_BOOK", () -> "key-456");
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
    FakeBooksApi fake;

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
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/" + slug))
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
        String calls = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/admin/api/integrations/upload-book/calls"))
                .build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(calls).contains("<3 bytes, base64>").doesNotContain("QUJD");
    }

    HttpResponse<String> callRaw(String method, String slugAndQuery, String contentType, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/" + slugAndQuery))
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
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + PORT + "/api/v1/integrations/" + slugAndQuery)).build(),
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

    @Test
    void adminPublishAndVersionsOverHttp() throws Exception {
        write("services/admin-book.json", service("admin-book", "POST", "/api/v1/Books", "body.title", null));
        HttpResponse<String> pub = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + PORT + "/admin/integrations/admin-book/publish"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(pub.statusCode()).isEqualTo(200);
        assertThat(pub.body()).contains("\"activeVersion\":\"1.0.0\"");

        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + PORT + "/admin/integrations/admin-book/versions/9.9.9/activate"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).contains("has no version 9.9.9").contains("\"integration\":\"admin-book\"");
    }
}
