package com.gfn.controlplane.security;

import com.gfn.controlplane.state.RedisStateStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Component
public class ApiAuthFilter extends OncePerRequestFilter {
    private static final String TOKEN_HEADER = "X-Control-Plane-Token";
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final RedisStateStore stateStore;
    private final String clientToken;
    private final String nodeToken;
    private final String adminToken;
    private final int clientCreateSessionRateLimitPerMinute;

    public ApiAuthFilter(
            RedisStateStore stateStore,
            @Value("${control-plane.auth.client-token:dev-client-token}") String clientToken,
            @Value("${control-plane.auth.node-token:dev-node-token}") String nodeToken,
            @Value("${control-plane.auth.admin-token:dev-admin-token}") String adminToken,
            @Value("${control-plane.rate-limit.client-create-session-per-minute:60}") int clientCreateSessionRateLimitPerMinute) {
        this.stateStore = stateStore;
        this.clientToken = clientToken;
        this.nodeToken = nodeToken;
        this.adminToken = adminToken;
        this.clientCreateSessionRateLimitPerMinute = clientCreateSessionRateLimitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            CallerContext context = authenticate(request);
            authorize(request, context);
            rateLimit(request, context);
            CallerContext.set(context);
            filterChain.doFilter(request, response);
        } catch (AuthException ex) {
            writeError(response, ex.status, ex.getMessage());
        } finally {
            CallerContext.clear();
        }
    }

    private CallerContext authenticate(HttpServletRequest request) {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + TOKEN_HEADER);
        }
        if (matches(token, adminToken)) {
            return new CallerContext(CallerRole.ADMIN, request.getHeader(TENANT_HEADER));
        }
        if (matches(token, nodeToken)) {
            return new CallerContext(CallerRole.NODE, request.getHeader(TENANT_HEADER));
        }
        if (matches(token, clientToken)) {
            String tenantId = request.getHeader(TENANT_HEADER);
            if (tenantId == null || tenantId.isBlank()) {
                throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + TENANT_HEADER);
            }
            return new CallerContext(CallerRole.CLIENT, tenantId);
        }
        throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Invalid control-plane token");
    }

    private void authorize(HttpServletRequest request, CallerContext context) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (context.role() == CallerRole.ADMIN) {
            return;
        }
        if (context.role() == CallerRole.NODE && method.equals("POST")
                && (path.equals("/api/v1/nodes/register") || path.matches("/api/v1/nodes/[^/]+/heartbeat"))) {
            return;
        }
        if (context.role() == CallerRole.CLIENT
                && ((method.equals("POST") && path.equals("/api/v1/sessions"))
                || (method.equals("GET") && path.matches("/api/v1/sessions/[^/]+"))
                || (method.equals("GET") && path.equals("/api/v1/capacity")))) {
            return;
        }
        throw new AuthException(HttpServletResponse.SC_FORBIDDEN, "Caller is not authorized for this endpoint");
    }

    private void rateLimit(HttpServletRequest request, CallerContext context) {
        if (context.role() != CallerRole.CLIENT
                || !request.getMethod().equals("POST")
                || !request.getRequestURI().equals("/api/v1/sessions")) {
            return;
        }
        long current = stateStore.incrementRateLimit(
                "client-create-session:" + context.tenantId(),
                Duration.ofMinutes(1)
        );
        if (current > clientCreateSessionRateLimitPerMinute) {
            throw new AuthException(HTTP_TOO_MANY_REQUESTS, "Session create rate limit exceeded");
        }
    }

    private boolean matches(String provided, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static class AuthException extends RuntimeException {
        private final int status;

        private AuthException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
