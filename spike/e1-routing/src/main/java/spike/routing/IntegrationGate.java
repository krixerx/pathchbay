package spike.routing;

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
 * Keeps callers away from a route while it is being replaced (R2/R7).
 * A request counts itself in-flight first and only then checks the gate, so swap() can
 * close the gate, wait for in-flight calls to drain, and replace the route with nobody inside it.
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
        AtomicInteger count = inFlight.computeIfAbsent(slug, k -> new AtomicInteger());
        count.incrementAndGet();
        if (closed.contains(slug)) {
            count.decrementAndGet();
            response.setStatus(503);
            response.setHeader("Retry-After", "2");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"integration-unavailable\",\"integration\":\"" + slug + "\",\"retryAfterSeconds\":2}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            count.decrementAndGet();
        }
    }

    /** Close the gate for one integration, wait until no call is inside its route, run the swap, reopen. */
    public void swap(String slug, ThrowingRunnable replaceRoute) throws Exception {
        closed.add(slug);
        try {
            AtomicInteger count = inFlight.computeIfAbsent(slug, k -> new AtomicInteger());
            long deadline = System.nanoTime() + 30_000_000_000L;
            // Rejected calls leave the count immediately, so this waits only for admitted calls.
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
