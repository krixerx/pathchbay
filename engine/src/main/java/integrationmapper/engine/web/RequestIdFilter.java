package integrationmapper.engine.web;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the caller's {@code X-Request-Id} when it is a short plain token, otherwise a new one.
 * It is echoed in the answer, put in the log context ({@code requestId}) and passed on to the integration's route as
 * the same header, so the call's log lines and its error answer can be matched. Like every inbound header it never
 * reaches the external system (OV-8). Runs first, so even a 401 carries the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String sent = request.getHeader(HEADER);
        String id = sent != null && SAFE.matcher(sent).matches() ? sent : UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(new WithId(request, id), response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** The request with {@code X-Request-Id} set to the id in use, replacing whatever the caller sent. */
    private static final class WithId extends HttpServletRequestWrapper {

        private final String id;

        WithId(HttpServletRequest request, String id) {
            super(request);
            this.id = id;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? id : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HEADER.equalsIgnoreCase(name) ? Collections.enumeration(java.util.List.of(id)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            java.util.List<String> names = new java.util.ArrayList<>(Collections.list(super.getHeaderNames()));
            names.removeIf(HEADER::equalsIgnoreCase);
            names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
