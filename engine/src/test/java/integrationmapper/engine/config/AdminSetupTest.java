package integrationmapper.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import integrationmapper.engine.testsupport.Admin;

/** First start without an admin login: only the console and setup answer until setup with the logged code is done. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminSetupTest {

    static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        dir = Files.createTempDirectory("admin-setup");
        r.add("mapper.config-dir", () -> dir.toString());
        // No login from the environment: blank the test engines' default.
        r.add(AdminLogin.USER_VARIABLE, () -> "");
        r.add(AdminLogin.PASSWORD_VARIABLE, () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    AdminLogin login;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> setup(String code, String user, String password) throws Exception {
        String body = "{\"code\":\"" + code + "\",\"user\":\"" + user + "\",\"password\":\"" + password + "\"}";
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/api/setup"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void beforeSetupOnlyTheConsoleAndSetupAnswer() throws Exception {
        assertThat(login.setupNeeded()).isTrue();
        assertThat(login.setupCodeForTests()).hasSize(26).matches("[A-Z2-7]+");
        assertThat(get("/", null).statusCode()).isNotIn(401, 403);
        assertThat(get("/admin/api/setup", null).body()).contains("\"needed\":true");
        HttpResponse<String> closed = get("/admin/api/integrations", null);
        assertThat(closed.statusCode()).isEqualTo(403);
        assertThat(closed.body()).contains("setup-required").contains("setup code");
        // A login that would be valid later is not accepted now either.
        assertThat(get("/admin/api/integrations", Admin.basic("admin", "a-long-enough-password")).statusCode()).isEqualTo(403);
    }

    @Test
    @Order(2)
    void aWrongCodeIsRefusedSlowlyAndWritesNothing() throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> r = setup("AAAA-BBBB", "admin", "a-long-enough-password");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(900_000_000L);
        assertThat(Files.exists(login.file())).isFalse();
        // The right code with a short password: 400, and the code still works afterwards.
        assertThat(setup(login.setupCodeForTests(), "admin", "short").statusCode()).isEqualTo(400);
        assertThat(login.setupNeeded()).isTrue();
    }

    @Test
    @Order(3)
    void setupWithTheCodeStoresAHashAndTurnsOnTheLogin() throws Exception {
        String code = login.setupCodeForTests();
        String grouped = String.join("-", code.split("(?<=\\G.{4})")).toLowerCase(); // as copied from the log, any case
        HttpResponse<String> r = setup(grouped, "admin", "a-long-enough-password");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);

        String file = Files.readString(login.file());
        assertThat(file).contains("\"admin\"").contains("pbkdf2-sha256$600000$").doesNotContain("a-long-enough-password");
        assertThat(login.file()).isEqualTo(dir.resolve("secrets/console/login.json"));

        assertThat(get("/admin/api/integrations", null).statusCode()).isEqualTo(401);
        assertThat(get("/admin/api/integrations", Admin.basic("admin", "wrong-password-here")).statusCode()).isEqualTo(401);
        assertThat(get("/admin/api/integrations", Admin.basic("admin", "a-long-enough-password")).statusCode()).isEqualTo(200);

        // The code worked once.
        HttpResponse<String> again = setup(code, "other", "another-long-password");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(Files.readString(login.file())).isEqualTo(file);
    }
}
