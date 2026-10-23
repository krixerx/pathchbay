package integrationmapper.engine;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Front door for {@code /api/v1/integrations/**}. Keeps callers away from an integration's route while it is
 * replaced (R2, R7; verified in spike E1), and refuses bodies the integration can't take: anything but JSON, except
 * multipart for an Input with files and a raw file for an Input with exactly one (D-29); those are refused unread
 * when they declare more bytes than {@code mapper.max-blob-bytes} allows.
 * A call counts itself in-flight before checking the gate, so swap() can close the gate, wait for
 * admitted calls to drain and replace the route with nobody inside it.
 */
@Component
public class IntegrationGate extends OncePerRequestFilter {

    static final String INTEGRATIONS = "/api/v1/integrations/";

    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();
    private final IntegrationRegistry registry;
    private final long maxBlobBytes;

    public IntegrationGate(IntegrationRegistry registry, @Value("${mapper.max-blob-bytes:52428800}") long maxBlobBytes) {
        this.registry = registry;
        this.maxBlobBytes = maxBlobBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTEGRATIONS);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String slug = request.getRequestURI().substring(INTEGRATIONS.length());
        // A body the integration can't take is refused here with the JSON error shape. Multipart is read by the route
        // itself (Spring's parsing is off), url-encoded forms are not taken at all.
        String contentType = request.getContentType();
        if (request.getContentLengthLong() != 0 && contentType != null
                && !contentType.toLowerCase().startsWith("application/json")) {
            CompiledIntegration ci = registry.find(slug);
            long files = ci == null ? 0 : Blobs.fileCount(ci.input());
            String type = contentType.toLowerCase();
            boolean multipart = Multipart.isMultipart(contentType);
            boolean otherForm = !multipart && (type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded"));
            boolean accepted = multipart ? files > 0 : !otherForm && files == 1;
            if (!accepted) {
                refuse(response, 415, "unsupported-media-type", slug, files == 0 ? "Send the request body as application/json"
                        : "Send the request body as application/json or multipart/form-data" + (files == 1 ? ", or the file as the raw body" : ""));
                return;
            }
            // Raw: the body is the file. Multipart: room for every file plus 1 MiB of text parts; each file is checked later.
            long allowed = multipart ? files * maxBlobBytes + (1 << 20) : maxBlobBytes;
            if (request.getContentLengthLong() > allowed) {
                refuse(response, 413, "payload-too-large", slug, (multipart ? "The body is " : "The file is ")
                        + request.getContentLengthLong() + " bytes, more than the limit of " + allowed + " bytes");
                return;
            }
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

    private static void refuse(HttpServletResponse response, int status, String error, String slug, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(Json.error(error, slug, detail));
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
