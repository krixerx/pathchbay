package integrationmapper.engine.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import integrationmapper.engine.config.MapperProperties;
import integrationmapper.engine.format.Blobs;
import integrationmapper.engine.format.Json;
import integrationmapper.engine.format.Multipart;
import integrationmapper.engine.runtime.CompiledIntegration;
import integrationmapper.engine.runtime.IntegrationRegistry;

/**
 * Front door for {@code /api/v1/integrations/**}. Keeps callers away from an integration's route while it is
 * replaced (R2, R7; verified in spike E1), and refuses bodies the integration can't take: anything but JSON, except
 * multipart for an Input with files and a raw file for an Input with exactly one (D-29). Every body is limited: a file
 * by {@code mapper.max-blob-bytes}, anything else by {@code mapper.max-body-bytes} plus room for the Input's files
 * as base64. A body that declares more is refused unread; one without a length (chunked) is read only up to the limit,
 * so a caller can't make the engine hold more than that in memory.
 * A call to a live integration counts itself in-flight before checking the gate, so swap() can close the gate, wait
 * for admitted calls to drain and replace the route with nobody inside it. Camel's platform-http answers
 * asynchronously: the filter returns while the call still runs, so it also runs on the async dispatch that ends the
 * call, and only then is the call counted out. Slugs nobody has published are not counted.
 */
@Component
public class IntegrationGate extends OncePerRequestFilter {

    public static final String INTEGRATIONS = "/api/v1/integrations/";

    private static final Logger log = LoggerFactory.getLogger(IntegrationGate.class);
    /** Request attribute: the slug this call was counted in for, so the last dispatch counts it out once. */
    private static final String COUNTED = IntegrationGate.class.getName() + ".counted";
    private static final long DRAIN_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Set<String> closed = ConcurrentHashMap.newKeySet();
    private final IntegrationRegistry registry;
    private final long maxBlobBytes;
    private final long maxBodyBytes;

    public IntegrationGate(IntegrationRegistry registry, MapperProperties properties) {
        this.registry = registry;
        this.maxBlobBytes = properties.maxBlobBytes();
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.of(request).startsWith(INTEGRATIONS);
    }

    /** The async dispatch is where an integration call really ends. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** Calls inside an integration's route now; for tests and diagnostics. */
    public int inFlight(String slug) {
        AtomicInteger count = inFlight.get(slug);
        return count == null ? 0 : count.get();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            try {
                chain.doFilter(request, response);
            } finally {
                countOutWhenDone(request);
            }
            return;
        }
        String slug = RequestPaths.of(request).substring(INTEGRATIONS.length());
        // A body the integration can't take is refused here with the JSON error shape. Multipart is read by the route
        // itself (Spring's parsing is off), url-encoded forms are not taken at all.
        String contentType = request.getContentType();
        CompiledIntegration ci = registry.find(slug);
        long files = ci == null ? 0 : Blobs.fileCount(ci.input());
        // JSON (or no type): the text fields plus every file of the Input as base64.
        long allowed = maxBodyBytes + files * ((maxBlobBytes + 2) / 3 * 4);
        String what = "The body is ";
        if (request.getContentLengthLong() != 0 && contentType != null
                && !contentType.toLowerCase().startsWith("application/json")) {
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
            allowed = multipart ? files * maxBlobBytes + (1 << 20) : maxBlobBytes;
            what = multipart ? "The body is " : "The file is ";
        }
        if (request.getContentLengthLong() > allowed) {
            refuse(response, 413, "payload-too-large", slug, what + request.getContentLengthLong() + " bytes, more than the limit of "
                    + allowed + " bytes");
            return;
        }
        byte[] body = request.getInputStream().readNBytes((int) Math.min(allowed + 1, Integer.MAX_VALUE - 8));
        if (body.length > allowed) {
            refuse(response, 413, "payload-too-large", slug, what + "more than the limit of " + allowed + " bytes");
            return;
        }
        request = new BufferedBody(request, body);
        AtomicInteger count = ci == null ? null : inFlight.computeIfAbsent(slug, k -> new AtomicInteger());
        if (count != null) {
            count.incrementAndGet();
            request.setAttribute(COUNTED, slug);
        }
        if (closed.contains(slug)) {
            countOut(request);
            response.setStatus(503);
            response.setHeader("Retry-After", "2");
            response.setContentType("application/json");
            response.getWriter().write(Json.error("integration-unavailable", slug, null));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            countOutWhenDone(request);
        }
    }

    /** Counts the call out unless it went async; then the async dispatch that ends it does. */
    private void countOutWhenDone(HttpServletRequest request) {
        if (!isAsyncStarted(request)) {
            countOut(request);
        }
    }

    private void countOut(HttpServletRequest request) {
        Object slug = request.getAttribute(COUNTED);
        if (slug != null) {
            request.removeAttribute(COUNTED);
            AtomicInteger count = inFlight.get((String) slug);
            if (count != null) {
                count.decrementAndGet();
            }
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
            AtomicInteger count = inFlight.get(slug);
            long deadline = System.nanoTime() + DRAIN_NANOS;
            while (count != null && count.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            if (count != null && count.get() > 0) {
                log.warn("{}: {} call(s) still inside after {} s; replacing the route anyway", slug, count.get(),
                        TimeUnit.NANOSECONDS.toSeconds(DRAIN_NANOS));
            }
            replaceRoute.run();
        } finally {
            closed.remove(slug);
            if (registry.find(slug) == null) {
                inFlight.remove(slug); // deleted, or never went live
            }
        }
    }

    /** The request with its body already read (and limited) by the gate. */
    static final class BufferedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    try {
                        listener.onDataAvailable();
                        listener.onAllDataRead();
                    } catch (IOException e) {
                        listener.onError(e);
                    }
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
