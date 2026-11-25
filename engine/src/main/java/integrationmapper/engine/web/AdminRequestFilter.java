package integrationmapper.engine.web;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import integrationmapper.engine.config.AdminLogin;
import integrationmapper.engine.config.MapperProperties;
import integrationmapper.engine.config.Packages;
import integrationmapper.engine.contract.ContractImporter;
import integrationmapper.engine.format.Json;

/**
 * The edge of the console and the admin API, decided on the path the router sees ({@link RequestPaths}):
 * <ol>
 * <li>the admin login (HTTP Basic, {@link AdminLogin}), or, before it exists, only the console's files and the setup
 * endpoint;</li>
 * <li>no changes from other sites: a write marked {@code Sec-Fetch-Site: cross-site} or with an {@code Origin} other
 * than the address it was sent to is refused, because browsers attach a cached Basic login to such requests;</li>
 * <li>a write with a body must be JSON ({@code application/zip} for package import), which a page on another site
 * can't send without a CORS preflight, and the engine allows none;</li>
 * <li>the body is read here up to its limit, so no admin request can fill the heap;</li>
 * <li>browser protections on every answer (CSP, no framing, nosniff).</li>
 * </ol>
 * Integration calls ({@code /api/...}) never pass through it; {@link InboundKeyFilter} decides those.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminRequestFilter extends OncePerRequestFilter {

    static final Pattern CONSOLE = Pattern.compile("/|/index\\.html|/favicon\\.svg|/assets/.+");
    static final String SETUP = "/admin/api/setup";
    static final String IMPORT = "/admin/api/import";
    /** Mantine sets inline styles and a style element, and the bundled fonts may come as data: URLs. */
    static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
    private static final Logger log = LoggerFactory.getLogger(AdminRequestFilter.class);

    private final AdminLogin login;
    private final long maxBodyBytes;
    private final long maxBlobBytes;

    public AdminRequestFilter(AdminLogin login, MapperProperties properties) {
        this.login = login;
        this.maxBodyBytes = properties.maxBodyBytes();
        this.maxBlobBytes = properties.maxBlobBytes();
    }

    static boolean isAdmin(String path) {
        return path.equals("/admin") || path.startsWith("/admin/");
    }

    static boolean isConsole(String path) {
        return CONSOLE.matcher(path).matches();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.of(request);
        return !isAdmin(path) && !isConsole(path) && !RequestPaths.hasDotSegment(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = RequestPaths.of(request);
        boolean dots = RequestPaths.hasDotSegment(path);
        String method = request.getMethod();
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");

        boolean setup = !dots && path.equals(SETUP);
        if (login.setupNeeded()) {
            boolean open = setup || (!dots && read && isConsole(path));
            if (!open) {
                refuse(response, 403, "setup-required", "Create the admin login first: open the console and enter the setup code from the engine's log");
                return;
            }
        } else if (!setup && !login.matches(request.getHeader("Authorization"))) {
            if (request.getHeader("Authorization") != null) {
                log.warn("Failed admin login from {} for {} {}", request.getRemoteAddr(), method, path);
            }
            response.setHeader("WWW-Authenticate", "Basic realm=\"Patchbay\", charset=\"UTF-8\"");
            refuse(response, 401, "unauthorized", "Log in with the admin user name and password");
            return;
        }

        boolean write = !read && !"OPTIONS".equals(method);
        boolean body = request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
        if (write && (isAdmin(path) || dots)) {
            if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site")) || !sameOrigin(request)) {
                refuse(response, 403, "cross-site", "Changes are accepted only from the console's own address. Behind a proxy that "
                        + "changes the host name or port, set SERVER_FORWARD_HEADERS_STRATEGY=native and let the proxy send "
                        + "X-Forwarded-Host and X-Forwarded-Proto");
                return;
            }
            if (body) {
                String wanted = path.equals(IMPORT) ? "application/zip" : "application/json";
                if (!isType(request.getContentType(), wanted)) {
                    refuse(response, 415, "unsupported-media-type", "Send the request body as " + wanted);
                    return;
                }
            }
        }
        if (body) {
            long limit = limit(path);
            if (request.getContentLengthLong() > limit) {
                refuse(response, 413, "payload-too-large", "The body is " + request.getContentLengthLong() + " bytes, more than the limit of " + limit + " bytes");
                return;
            }
            byte[] bytes = request.getInputStream().readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > limit) {
                refuse(response, 413, "payload-too-large", "The body is more than the limit of " + limit + " bytes");
                return;
            }
            request = new IntegrationGate.BufferedBody(request, bytes);
        }
        chain.doFilter(request, response);
    }

    /**
     * The largest body a path takes: a package ZIP; a contract, which may carry a WSDL ZIP as base64; a test, which may
     * carry a file as base64; anything else {@code mapper.max-body-bytes}.
     */
    long limit(String path) {
        if (path.equals(IMPORT)) {
            return Packages.MAX_BYTES;
        }
        if (path.equals("/admin/api/contracts") || path.startsWith("/admin/api/contracts/")) {
            return maxBodyBytes + base64(ContractImporter.MAX_ZIP_BYTES);
        }
        if (path.startsWith("/admin/api/integrations/") && path.endsWith("/test")) {
            return maxBodyBytes + base64(maxBlobBytes);
        }
        return maxBodyBytes;
    }

    private static long base64(long bytes) {
        return (bytes + 2) / 3 * 4;
    }

    /**
     * True without an {@code Origin} (scripts, curl), or when it names the address the request was sent to: the same
     * host and port as the {@code Host} header (default ports left out, so a TLS proxy that keeps {@code Host} needs
     * nothing), or as the address Spring resolved, which behind a trusted proxy
     * ({@code server.forward-headers-strategy}) comes from {@code X-Forwarded-Host} and {@code X-Forwarded-Proto}.
     */
    static boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        URI u;
        try {
            u = new URI(origin.strip());
        } catch (URISyntaxException e) {
            return false;
        }
        if (u.getHost() == null || u.getScheme() == null) {
            return false; // "null" (sandboxed frames, file:) or nonsense
        }
        String host = request.getHeader("Host");
        if (host != null && authority(u.getRawAuthority()).equals(authority(host))) {
            return true;
        }
        int port = u.getPort() != -1 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        return bare(u.getHost()).equalsIgnoreCase(bare(request.getServerName())) && port == request.getServerPort();
    }

    /** host[:port] in lower case, without a default port (80, 443). */
    private static String authority(String value) {
        String a = value.strip().toLowerCase(Locale.ROOT);
        return a.endsWith(":80") ? a.substring(0, a.length() - 3) : a.endsWith(":443") ? a.substring(0, a.length() - 4) : a;
    }

    private static String bare(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : String.valueOf(host);
    }

    private static boolean isType(String contentType, String wanted) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).strip().equals(wanted);
    }

    private static void refuse(HttpServletResponse response, int status, String error, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(Json.error(error, null, detail));
    }
}
