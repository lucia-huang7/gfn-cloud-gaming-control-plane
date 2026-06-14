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
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ApiAuthFilter extends OncePerRequestFilter {
    private static final String TOKEN_HEADER = "X-Control-Plane-Token";
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String NODE_HEADER = "X-Node-Id";
    private static final String NODE_CREDENTIAL_HEADER = "X-Node-Credential";
    private static final String NODE_CREDENTIAL_VERSION_HEADER = "X-Node-Credential-Version";
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final RedisStateStore stateStore;
    private final String clientToken;
    private final String nodeToken;
    private final String adminToken;
    private final Map<String, NodeCredential> nodeCredentials;
    private final int clientCreateSessionRateLimitPerMinute;

    public ApiAuthFilter(
            RedisStateStore stateStore,
            @Value("${control-plane.auth.client-token:dev-client-token}") String clientToken,
            @Value("${control-plane.auth.node-token:dev-node-token}") String nodeToken,
            @Value("${control-plane.auth.admin-token:dev-admin-token}") String adminToken,
            @Value("${control-plane.auth.node-credentials:}") String nodeCredentialConfig,
            @Value("${control-plane.rate-limit.client-create-session-per-minute:60}") int clientCreateSessionRateLimitPerMinute) {
        this.stateStore = stateStore;
        this.clientToken = clientToken;
        this.nodeToken = nodeToken;
        this.adminToken = adminToken;
        this.nodeCredentials = parseNodeCredentials(nodeCredentialConfig);
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
        CallerContext context = null;
        try {
            context = authenticate(request);
            authorize(request, context);
            rateLimit(request, context);
            audit(request, context, "ALLOW", null);
            CallerContext.set(context);
            filterChain.doFilter(request, response);
        } catch (AuthException ex) {
            audit(request, context, "DENY", ex.getMessage());
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
            return new CallerContext(CallerRole.ADMIN, request.getHeader(TENANT_HEADER), request.getHeader(NODE_HEADER));
        }
        if (matches(token, clientToken)) {
            String tenantId = request.getHeader(TENANT_HEADER);
            if (tenantId == null || tenantId.isBlank()) {
                throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + TENANT_HEADER);
            }
            return new CallerContext(CallerRole.CLIENT, tenantId);
        }
        Optional<CallerContext> nodeCaller = authenticateNode(request, token);
        if (nodeCaller.isPresent()) {
            return nodeCaller.get();
        }
        throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Invalid control-plane token");
    }

    private Optional<CallerContext> authenticateNode(HttpServletRequest request, String token) {
        String nodeId = request.getHeader(NODE_HEADER);
        if (nodeId == null || nodeId.isBlank()) {
            if (matches(token, nodeToken) || !nodeCredentials.isEmpty()) {
                throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + NODE_HEADER);
            }
            return Optional.empty();
        }
        if (!nodeCredentials.isEmpty()) {
            String version = request.getHeader(NODE_CREDENTIAL_VERSION_HEADER);
            String credential = request.getHeader(NODE_CREDENTIAL_HEADER);
            if (version == null || version.isBlank()) {
                throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + NODE_CREDENTIAL_VERSION_HEADER);
            }
            if (credential == null || credential.isBlank()) {
                throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Missing " + NODE_CREDENTIAL_HEADER);
            }
            NodeCredential expected = nodeCredentials.get(nodeKey(nodeId, version));
            if (expected == null || !matches(credential, expected.secret())) {
                throw new AuthException(HttpServletResponse.SC_UNAUTHORIZED, "Invalid node credential");
            }
            return Optional.of(new CallerContext(CallerRole.NODE, request.getHeader(TENANT_HEADER), nodeId, version));
        }
        if (matches(token, nodeToken)) {
            return Optional.of(new CallerContext(CallerRole.NODE, request.getHeader(TENANT_HEADER), nodeId, "dev-shared"));
        }
        return Optional.empty();
    }

    private void authorize(HttpServletRequest request, CallerContext context) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (context.role() == CallerRole.ADMIN) {
            return;
        }
        if (context.role() == CallerRole.NODE && method.equals("POST")
                && path.equals("/api/v1/nodes/register")) {
            return;
        }
        if (context.role() == CallerRole.NODE && method.equals("POST")
                && path.matches("/api/v1/nodes/[^/]+/heartbeat")
                && context.nodeId().equals(pathNodeId(path))) {
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

    private String pathNodeId(String path) {
        String prefix = "/api/v1/nodes/";
        String suffix = "/heartbeat";
        return path.substring(prefix.length(), path.length() - suffix.length());
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

    private Map<String, NodeCredential> parseNodeCredentials(String config) {
        if (config == null || config.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.split(":", 3))
                .filter(parts -> parts.length == 3)
                .map(parts -> new NodeCredential(parts[0], parts[1], parts[2]))
                .collect(Collectors.toUnmodifiableMap(
                        credential -> nodeKey(credential.nodeId(), credential.version()),
                        credential -> credential,
                        (left, right) -> right
                ));
    }

    private String nodeKey(String nodeId, String version) {
        return nodeId + ":" + version;
    }

    private void audit(HttpServletRequest request, CallerContext context, String outcome, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("outcome", outcome);
        event.put("method", request.getMethod());
        event.put("path", request.getRequestURI());
        event.put("role", context == null ? null : context.role().name());
        event.put("tenantId", context == null ? request.getHeader(TENANT_HEADER) : context.tenantId());
        event.put("nodeId", context == null ? request.getHeader(NODE_HEADER) : context.nodeId());
        event.put("credentialVersion", context == null ? request.getHeader(NODE_CREDENTIAL_VERSION_HEADER) : context.credentialVersion());
        event.put("reason", reason);
        try {
            stateStore.recordAuthAudit(event, Duration.ofDays(7), 10_000);
        } catch (RuntimeException ignored) {
            // Auth decisions should not depend on audit sink availability.
        }
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

    private record NodeCredential(String nodeId, String version, String secret) {
    }
}
