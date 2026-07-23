package com.nbcb.agent.governance.interceptor;

import com.nbcb.agent.governance.config.AgentGovernanceManager;
import com.nbcb.agent.governance.entity.AgentGovernanceEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGovernanceApiInterceptorTest {

    private final AgentGovernanceManager governanceManager =
            mock(AgentGovernanceManager.class);
    private final AgentGovernanceApiInterceptor interceptor =
            new AgentGovernanceApiInterceptor(governanceManager);

    @Test
    void shouldBlockImmediatelyWhenRouteConfigured() throws Exception {
        AgentGovernanceEntity route = new AgentGovernanceEntity();
        route.setPathPattern("/api/blocked/**");
        when(governanceManager.getRoutes()).thenReturn(List.of(route));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/blocked/test");
        request.addHeader("X-Channel-Code", "CHANNEL_A");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getContentAsString())
                .contains("ROUTE_BLOCKED");
        verify(governanceManager, never())
                .isBlocked("CHANNEL_A", null, null);
    }

    @Test
    void shouldCheckGlobalScopeWhenRouteNotConfigured() throws Exception {
        when(governanceManager.getRoutes()).thenReturn(List.of());
        when(governanceManager.isBlocked("CHANNEL_A", "U1", "O1"))
                .thenReturn(true);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/free");
        request.addHeader("X-Channel-Code", "CHANNEL_A");
        request.addHeader("X-User-Id", "U1");
        request.addHeader("X-Org-Id", "O1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getContentAsString())
                .contains("SCOPE_BLOCKED");
    }

    @Test
    void shouldAllowWhenNoRouteAndNoScopeBlocked() throws Exception {
        when(governanceManager.getRoutes()).thenReturn(List.of());
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/free");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
