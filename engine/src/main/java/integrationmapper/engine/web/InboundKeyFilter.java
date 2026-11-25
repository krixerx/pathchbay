package integrationmapper.engine.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import integrationmapper.engine.format.Json;

/**
 * The optional inbound key of integration callers (slice plan "Inbound access"): when the environment variable
 * {@code INBOUND_API_KEY} is set, every request answers 401 unless it carries the same value in {@code X-API-Key},
 * except the console's page and static files, the admin API (both behind the admin login, {@link AdminRequestFilter}),
 * the published OpenAPI documents and the health probes. Deny by default, decided on the path
 * the router sees ({@link RequestPaths}), so an encoded or {@code ;}-parameter path can't slip past. Without the
 * variable nothing is checked (closed Docker network). The header never reaches the external system: the routes
 * remove all inbound headers before the outbound call (OV-8).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InboundKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    /** Open without the key: the console (a hash router, so no deep links), the OpenAPI documents and the health probes. */
    private static final Pattern OPEN = Pattern.compile(
            "/|/index\\.html|/favicon\\.svg|/assets/.+|/api/openapi\\.json|/api/openapi/[^/]+\\.json|/actuator/health(/liveness|/readiness)?");

    private final byte[] key;

    public InboundKeyFilter(@Value("${INBOUND_API_KEY:}") String key) {
        this.key = key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (key == null) {
            return true;
        }
        String path = RequestPaths.of(request);
        if (RequestPaths.hasDotSegment(path)) {
            return false;
        }
        // The console and the admin API have their own login (AdminRequestFilter): callers' key never opens them.
        if (AdminRequestFilter.isAdmin(path)) {
            return true;
        }
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        return read && OPEN.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        // Constant-time comparison, so the key can't be guessed from response times.
        if (sent != null && MessageDigest.isEqual(key, sent.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write(Json.error("unauthorized", null,
                sent == null ? "Send the X-API-Key header" : "The X-API-Key header does not match"));
    }
}
