package integrationmapper.engine.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import integrationmapper.engine.format.Json;

/**
 * The one admin login of the console and the admin API (slice plan: "one admin login from env"). The environment wins:
 * {@code MAPPER_ADMIN_USER} and {@code MAPPER_ADMIN_PASSWORD}, both or neither. Otherwise
 * {@code <config-dir>/secrets/console/login.json} holds the user name and a PBKDF2 hash of the password, never the
 * password itself (its folder can't clash with an integration's secret file, since slugs have no {@code /}). With
 * neither, the engine is in setup mode: it logs a one-time code at every start, and the console's setup page creates
 * the file with it. Only someone who can read the engine's log can do that, not whoever opens the console first.
 */
@Component
public class AdminLogin {

    public static final String USER_VARIABLE = "MAPPER_ADMIN_USER";
    public static final String PASSWORD_VARIABLE = "MAPPER_ADMIN_PASSWORD";
    public static final int MIN_PASSWORD_LENGTH = 12;
    static final int ITERATIONS = 600_000;
    private static final String SCHEME = "pbkdf2-sha256";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long WRONG_CODE_DELAY_MS = 1000;
    private static final Logger log = LoggerFactory.getLogger(AdminLogin.class);

    /** A setup that is refused, with the status the caller gets. */
    public static final class SetupRefused extends RuntimeException {

        private final int status;

        SetupRefused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private record Stored(String user, String hash) {
    }

    private final ConfigStore store;
    private final SecureRandom random = new SecureRandom();
    private final byte[] envUser;
    private final byte[] envPassword;
    private volatile Stored stored;
    /** The setup code without dashes, or null when a login exists. Kept only in memory. */
    private volatile String setupCode;
    /** SHA-256 of Authorization values whose PBKDF2 check passed, so the slow hash runs once per login, not per request. */
    private final Set<String> verified = ConcurrentHashMap.newKeySet();
    /** At most two PBKDF2 checks at a time, so wrong passwords can't occupy every core. */
    private final Semaphore hashing = new Semaphore(2);
    /**
     * One setup attempt at a time. A lock, not {@code synchronized}: on Java 21 a virtual thread that sleeps or waits
     * inside a monitor pins its carrier thread, so a burst of wrong codes could stall every other request.
     */
    private final ReentrantLock setupLock = new ReentrantLock(true);

    public AdminLogin(Environment env, ConfigStore store) {
        this.store = store;
        String user = blankToNull(env.getProperty(USER_VARIABLE));
        String password = blankToNull(env.getProperty(PASSWORD_VARIABLE));
        if ((user == null) != (password == null)) {
            throw new IllegalStateException("Set both " + USER_VARIABLE + " and " + PASSWORD_VARIABLE + ", or neither: "
                    + (user == null ? USER_VARIABLE : PASSWORD_VARIABLE) + " is missing");
        }
        envUser = user == null ? null : user.getBytes(StandardCharsets.UTF_8);
        envPassword = password == null ? null : password.getBytes(StandardCharsets.UTF_8);
        if (user == null) {
            stored = readFile();
            if (stored == null) {
                setupCode = newCode();
                log.warn("No admin login yet. Open the console and create it with setup code {} (valid until it is used or the engine "
                        + "restarts). Or set {} and {}.", grouped(setupCode), USER_VARIABLE, PASSWORD_VARIABLE);
            }
        }
    }

    /** The file the login is kept in when the environment doesn't give one. */
    public Path file() {
        return store.root().resolve("secrets").resolve("console").resolve("login.json");
    }

    public boolean setupNeeded() {
        return setupCode != null;
    }

    /** Whether an {@code Authorization} header carries the admin login. */
    public boolean matches(String authorization) {
        if (authorization == null || authorization.length() < 7 || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        byte[] user = decoded.substring(0, colon).getBytes(StandardCharsets.UTF_8);
        String password = decoded.substring(colon + 1);
        if (envUser != null) {
            // Both compared every time, over digests, so neither the time nor the length tells which part was wrong.
            boolean u = MessageDigest.isEqual(sha256(user), sha256(envUser));
            boolean p = MessageDigest.isEqual(sha256(password.getBytes(StandardCharsets.UTF_8)), sha256(envPassword));
            return u & p;
        }
        Stored s = stored;
        if (s == null) {
            return false;
        }
        String key = HexFormat.of().formatHex(sha256(authorization.getBytes(StandardCharsets.UTF_8)));
        if (verified.contains(key)) {
            return true;
        }
        boolean u = MessageDigest.isEqual(sha256(user), sha256(s.user().getBytes(StandardCharsets.UTF_8)));
        boolean p;
        hashing.acquireUninterruptibly();
        try {
            p = verifyHash(password, s.hash());
        } finally {
            hashing.release();
        }
        if (u & p) {
            if (verified.size() > 32) {
                verified.clear();
            }
            verified.add(key);
            return true;
        }
        return false;
    }

    /**
     * Creates the login with the code from the log. A wrong code is answered after a delay, one attempt at a time; the
     * code works once.
     */
    public void setup(String code, String user, String password) throws IOException {
        setupLock.lock();
        try {
            if (setupCode == null) {
                throw new SetupRefused(409, "The admin login already exists. To create it again, delete " + file() + " and restart the engine");
            }
            String sent = code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
            if (!MessageDigest.isEqual(sha256(sent.getBytes(StandardCharsets.UTF_8)), sha256(setupCode.getBytes(StandardCharsets.UTF_8)))) {
                try {
                    Thread.sleep(WRONG_CODE_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new SetupRefused(403, "The setup code is not the one in the engine's log");
            }
            String u = user == null ? "" : user.strip();
            if (u.isEmpty() || u.length() > 100 || u.contains(":") || u.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Give a user name of up to 100 characters, without ':' or control characters");
            }
            if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("The password needs at least " + MIN_PASSWORD_LENGTH + " characters");
            }
            if (password.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("The password can't contain control characters");
            }
            String hash = hash(password);
            SecretResolver.writePrivate(store, file(), Map.of("user", u, "hash", hash));
            stored = new Stored(u, hash);
            verified.clear();
            setupCode = null;
            log.info("Admin login created for user {}", u);
        } finally {
            setupLock.unlock();
        }
    }

    // ---- file and hash

    private Stored readFile() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode n = Json.MAPPER.readTree(file.toFile());
            String user = n.path("user").asText("");
            String hash = n.path("hash").asText("");
            if (user.isBlank() || !hash.startsWith(SCHEME + "$") || hash.split("\\$").length != 4) {
                throw new IllegalStateException("Admin login file " + file + " needs \"user\" and a \"hash\"; delete it and restart to create the login again");
            }
            return new Stored(user, hash);
        } catch (IOException e) {
            throw new IllegalStateException("Admin login file " + file + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return SCHEME + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derived);
    }

    static boolean verifyHash(String password, String hash) {
        String[] parts = hash.split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    // ---- helpers

    /** 128 random bits as 26 base32 characters. */
    private String newCode() {
        byte[] bytes = new byte[17];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5 && sb.length() < 26) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return sb.toString();
    }

    private static String grouped(String code) {
        return String.join("-", code.split("(?<=\\G.{4})"));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** For tests: the current setup code, null when a login exists. */
    String setupCodeForTests() {
        return setupCode;
    }
}
