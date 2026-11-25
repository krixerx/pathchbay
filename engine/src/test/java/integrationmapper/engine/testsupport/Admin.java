package integrationmapper.engine.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** The admin login every test engine runs with (src/test/resources/config/application.properties). */
public final class Admin {

    public static final String USER = "test-admin";
    public static final String PASSWORD = "test-admin-password";
    /** The Authorization header value for admin calls. */
    public static final String AUTHORIZATION = basic(USER, PASSWORD);

    private Admin() {
    }

    public static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
