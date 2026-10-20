package integrationmapper.engine;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Front door for {@code /api/v1/integrations/**}. Keeps callers away from an integration's route while it is
 * replaced (R2, R7; verified in spike E1), and refuses non-JSON bodies.
 * A call counts itself in-flight before checking the gate, so swap() can close the gate, wait for
 * admitted calls to drain and replace the route with nobody inside it.
 */
@Component
public class IntegrationGate extends OncePerRequestFilter {

    static final String INTEGRATIONS = "/api/v1/integrations/";

    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTEGRATIONS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String slug = request.getRequestURI().substring(INTEGRATIONS.length());
        // Camel's platform-http consumer parses form bodies before the route runs and answers with an HTML 500,
        // so a body that isn't JSON is refused here with the JSON error shape.
        String contentType = request.getContentType();
        if (request.getContentLengthLong() != 0 && contentType != null
                && !contentType.toLowerCase().startsWith("application/json")) {
            response.setStatus(415);
            response.setContentType("application/json");
            response.getWriter().write(Json.error("unsupported-media-type", slug, "Send the request body as application/json"));
            return;
        }
        AtomicInteger count = inFlight.computeIfAbsent(slug, k -> new AtomicInteger());
        count.incrementAndGet();
        if (closed.contains(slug)) {
            count.decrementAndGet();
            response.setStatus(503);
            response.setHeader("Retry-After", "2");
            response.setContentType("application/json");
            response.getWriter().write(Json.error("integration-unavailable", slug, null));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            count.decrementAndGet();
        }
    }

    /** Close the gate for one integration, wait until no admitted call is inside it, run the swap, reopen. */
    public void swap(String slug, ThrowingRunnable replaceRoute) throws Exception {
        closed.add(slug);
        try {
            AtomicInteger count = inFlight.computeIfAbsent(slug, k -> new AtomicInteger());
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (count.get() > 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            replaceRoute.run();
        } finally {
            closed.remove(slug);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
