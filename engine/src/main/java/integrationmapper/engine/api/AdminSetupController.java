package integrationmapper.engine.api;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import integrationmapper.engine.config.AdminLogin;
import integrationmapper.engine.format.Json;

/**
 * First start: whether the admin login still has to be created, and creating it with the setup code from the
 * engine's log ({@link AdminLogin}). The only admin endpoints that answer before the login exists.
 */
@RestController
public class AdminSetupController {

    private final AdminLogin login;

    public AdminSetupController(AdminLogin login) {
        this.login = login;
    }

    record SetupRequest(String code, String user, String password) {
    }

    @GetMapping("/admin/api/setup")
    public Map<String, Object> status() {
        return Map.of("needed", login.setupNeeded(), "minPasswordLength", AdminLogin.MIN_PASSWORD_LENGTH);
    }

    @PostMapping(value = "/admin/api/setup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> setup(@RequestBody String body) throws IOException {
        SetupRequest req;
        try {
            req = Json.MAPPER.readValue(body, SetupRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body is not valid: " + e.getOriginalMessage());
        }
        login.setup(req.code(), req.user(), req.password());
        return Map.of("needed", false);
    }

    @ExceptionHandler(AdminLogin.SetupRefused.class)
    public ResponseEntity<String> refused(AdminLogin.SetupRefused e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                .body(Json.error(e.status() == 409 ? "already-set-up" : "wrong-setup-code", null, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(Json.error("bad-request", null, e.getMessage()));
    }
}
