package integrationmapper.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The optional inbound key (slice plan "Inbound access"): when the environment variable {@code INBOUND_API_KEY} is
 * set, published integrations and the console/admin API answer 401 unless the request carries the same value in
 * {@code X-API-Key}. The console's page and its static files stay open so it can ask for the key. Without the
 * variable nothing is checked (closed Docker network). The header never reaches the external system: the routes
 * remove all inbound headers before the outbound call (OV-8).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InboundKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final byte[] key;

    public InboundKeyFilter(@Value("${INBOUND_API_KEY:}") String key) {
        this.key = key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return key == null || !(uri.startsWith(IntegrationGate.INTEGRATIONS) || uri.startsWith("/admin/"));
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
