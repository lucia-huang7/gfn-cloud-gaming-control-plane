package com.gfn.controlplane.security;

import com.gfn.controlplane.state.RedisStateStore;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAuthFilterTest {
    private final RedisStateStore stateStore = mock(RedisStateStore.class);
    private final ApiAuthFilter filter = new ApiAuthFilter(
            stateStore,
            "client-token",
            "node-token",
            "admin-token",
            "",
            1
    );

    @Test
    void rejectsMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/nodes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nodeTokenCannotCreateSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sessions");
        request.addHeader("X-Control-Plane-Token", "node-token");
        request.addHeader("X-Node-Id", "node-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void nodeTokenRequiresNodeIdentityHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/nodes/node-1/heartbeat");
        request.addHeader("X-Control-Plane-Token", "node-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nodeTokenCannotHeartbeatDifferentNodeId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/nodes/node-2/heartbeat");
        request.addHeader("X-Control-Plane-Token", "node-token");
        request.addHeader("X-Node-Id", "node-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nodeTokenCanHeartbeatOwnNodeId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/nodes/node-1/heartbeat");
        request.addHeader("X-Control-Plane-Token", "node-token");
        request.addHeader("X-Node-Id", "node-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        verify(stateStore).recordAuthAudit(any(), eq(Duration.ofDays(7)), eq(10_000L));
    }

    @Test
    void perNodeCredentialCanHeartbeatOwnNodeId() throws Exception {
        ApiAuthFilter perNodeFilter = new ApiAuthFilter(
                stateStore,
                "client-token",
                "node-token",
                "admin-token",
                "node-1:v1:node-secret,node-1:v2:rotated-secret",
                1
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/nodes/node-1/heartbeat");
        request.addHeader("X-Control-Plane-Token", "not-shared-node-token");
        request.addHeader("X-Node-Id", "node-1");
        request.addHeader("X-Node-Credential-Version", "v1");
        request.addHeader("X-Node-Credential", "node-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        perNodeFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void perNodeCredentialSupportsRotatedVersion() throws Exception {
        ApiAuthFilter perNodeFilter = new ApiAuthFilter(
                stateStore,
                "client-token",
                "node-token",
                "admin-token",
                "node-1:v1:node-secret,node-1:v2:rotated-secret",
                1
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/nodes/node-1/heartbeat");
        request.addHeader("X-Control-Plane-Token", "not-shared-node-token");
        request.addHeader("X-Node-Id", "node-1");
        request.addHeader("X-Node-Credential-Version", "v2");
        request.addHeader("X-Node-Credential", "rotated-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        perNodeFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void perNodeCredentialModeRejectsSharedNodeTokenFallback() throws Exception {
        ApiAuthFilter perNodeFilter = new ApiAuthFilter(
                stateStore,
                "client-token",
                "node-token",
                "admin-token",
                "node-1:v1:node-secret",
                1
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/nodes/node-1/heartbeat");
        request.addHeader("X-Control-Plane-Token", "node-token");
        request.addHeader("X-Node-Id", "node-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        perNodeFilter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void clientCreateSessionIsTenantRateLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sessions");
        request.addHeader("X-Control-Plane-Token", "client-token");
        request.addHeader("X-Tenant-Id", "tenant-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(stateStore.incrementRateLimit(eq("client-create-session:tenant-a"), any(Duration.class))).thenReturn(2L);

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(429);
    }
}
