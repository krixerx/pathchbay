package integrationmapper.engine.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.util.UrlPathHelper;

/**
 * The path a request is routed by: percent-decoded, without {@code ;parameters} and with double slashes merged, the
 * way Spring MVC and Camel platform-http match it. Filters must decide on this path, not on
 * {@code getRequestURI()}: {@code /%61dmin/...} or {@code /admin;x/...} reach the admin API too.
 */
final class RequestPaths {

    private static final UrlPathHelper HELPER = new UrlPathHelper();

    static {
        HELPER.setUrlDecode(true);
        HELPER.setRemoveSemicolonContent(true);
    }

    private RequestPaths() {
    }

    static String of(HttpServletRequest request) {
        return HELPER.getPathWithinApplication(request);
    }

    /** True when a segment is {@code .} or {@code ..}: such a path is never one of the open ones. */
    static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
