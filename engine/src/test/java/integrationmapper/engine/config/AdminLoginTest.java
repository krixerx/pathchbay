package integrationmapper.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import integrationmapper.engine.testsupport.Admin;

/** Where the admin login comes from and how it is checked (AdminLogin), without a server. */
class AdminLoginTest {

    @TempDir
    Path dir;

    AdminLogin login(MockEnvironment env) {
        return new AdminLogin(env, new ConfigStore(dir.toString()));
    }

    @Test
    void theEnvironmentsLoginIsCheckedExactly() {
        AdminLogin login = login(new MockEnvironment().withProperty("MAPPER_ADMIN_USER", "ops").withProperty("MAPPER_ADMIN_PASSWORD", "secret-value-1"));
        assertThat(login.setupNeeded()).isFalse();
        assertThat(login.matches(Admin.basic("ops", "secret-value-1"))).isTrue();
        assertThat(login.matches("basic " + Admin.basic("ops", "secret-value-1").substring(6))).isTrue();
        assertThat(login.matches(Admin.basic("ops", "secret-value-2"))).isFalse();
        assertThat(login.matches(Admin.basic("Ops", "secret-value-1"))).isFalse();
        assertThat(login.matches(null)).isFalse();
        assertThat(login.matches("Bearer x")).isFalse();
        assertThat(login.matches("Basic !!!")).isFalse();
        assertThat(login.matches("Basic " + java.util.Base64.getEncoder().encodeToString("no-colon".getBytes()))).isFalse();
    }

    @Test
    void onlyOneVariableStopsTheStart() {
        assertThatThrownBy(() -> login(new MockEnvironment().withProperty("MAPPER_ADMIN_USER", "ops")))
                .hasMessageContaining("MAPPER_ADMIN_PASSWORD is missing");
        assertThatThrownBy(() -> login(new MockEnvironment().withProperty("MAPPER_ADMIN_PASSWORD", "x")))
                .hasMessageContaining("MAPPER_ADMIN_USER is missing");
    }

    @Test
    void theHashRoundTripsAndNeverHoldsThePassword() {
        AdminLogin login = login(new MockEnvironment());
        String hash = login.hash("correct horse battery");
        assertThat(hash).startsWith("pbkdf2-sha256$600000$").doesNotContain("correct horse battery");
        assertThat(AdminLogin.verifyHash("correct horse battery", hash)).isTrue();
        assertThat(AdminLogin.verifyHash("correct horse batterY", hash)).isFalse();
        assertThat(login.hash("correct horse battery")).isNotEqualTo(hash); // a new salt every time
    }

    @Test
    void theEnvironmentWinsOverTheFileAndTheFileIsReadOtherwise() throws Exception {
        AdminLogin first = login(new MockEnvironment());
        assertThat(first.setupNeeded()).isTrue();
        first.setup(first.setupCodeForTests(), "admin", "a-long-enough-password");

        AdminLogin fromFile = login(new MockEnvironment());
        assertThat(fromFile.setupNeeded()).isFalse();
        assertThat(fromFile.matches(Admin.basic("admin", "a-long-enough-password"))).isTrue();
        assertThat(fromFile.matches(Admin.basic("admin", "a-long-enough-password"))).isTrue(); // from the cache now

        AdminLogin fromEnv = login(new MockEnvironment().withProperty("MAPPER_ADMIN_USER", "ops").withProperty("MAPPER_ADMIN_PASSWORD", "secret-value-1"));
        assertThat(fromEnv.matches(Admin.basic("admin", "a-long-enough-password"))).isFalse();
        assertThat(fromEnv.matches(Admin.basic("ops", "secret-value-1"))).isTrue();
    }

    @Test
    void deletingTheFileStartsSetupAgainWithANewCode() throws Exception {
        AdminLogin first = login(new MockEnvironment());
        String code = first.setupCodeForTests();
        first.setup(code, "admin", "a-long-enough-password");
        Files.delete(first.file());

        AdminLogin restarted = login(new MockEnvironment());
        assertThat(restarted.setupNeeded()).isTrue();
        assertThat(restarted.setupCodeForTests()).isNotEqualTo(code);
        assertThatThrownBy(() -> restarted.setup(code, "admin", "a-long-enough-password"))
                .isInstanceOf(AdminLogin.SetupRefused.class).hasMessageContaining("not the one in the engine's log");
    }

    @Test
    void aDamagedFileStopsTheStart() throws Exception {
        Path file = dir.resolve("secrets/console/login.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"user\":\"admin\"}");
        assertThatThrownBy(() -> login(new MockEnvironment())).hasMessageContaining("needs \"user\" and a \"hash\"");
    }
}
