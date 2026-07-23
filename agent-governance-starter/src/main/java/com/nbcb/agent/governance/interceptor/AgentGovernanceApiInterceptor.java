package com.nbcb.agent.governance.interceptor;

import com.nbcb.agent.governance.config.AgentGovernanceManager;
import com.nbcb.agent.governance.entity.AgentGovernanceEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * API 层统一治理拦截器。
 * <p>
 * 处理顺序：
 * <ol>
 *   <li>先匹配 ROUTE 配置，命中 URL 黑名单直接拦截。</li>
 *   <li>URL 未配置时，再校验全局渠道、用户、机构禁用配置。</li>
 *   <li>所有维度未命中禁用配置时放行。</li>
 * </ol>
 *
 * @author com.nbcb
 */
@Slf4j
public class AgentGovernanceApiInterceptor implements HandlerInterceptor {

    private static final String HEADER_CHANNEL_CODE = "X-Channel-Code";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_ORG_ID = "X-Org-Id";

    private final AgentGovernanceManager governanceManager;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AgentGovernanceApiInterceptor(AgentGovernanceManager governanceManager) {
        this.governanceManager = governanceManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        AgentGovernanceEntity route = matchRoute(request.getRequestURI());
        if (route != null) {
            writeReject(response, route, "ROUTE_BLOCKED");
            log.warn("Agent API blocked by route blacklist. uri={}",
                    request.getRequestURI());
            return false;
        }

        String channelCode = request.getHeader(HEADER_CHANNEL_CODE);
        String userId = request.getHeader(HEADER_USER_ID);
        String orgId = request.getHeader(HEADER_ORG_ID);
        if (governanceManager.isBlocked(channelCode, userId, orgId)) {
            writeReject(response, null, "SCOPE_BLOCKED");
            log.warn("Agent API blocked by global scope. channelCode={} userId={} orgId={}",
                    channelCode, userId, orgId);
            return false;
        }

        return true;
    }

    private AgentGovernanceEntity matchRoute(String uri) {
        List<AgentGovernanceEntity> routes = governanceManager.getRoutes();
        if (routes == null) {
            return null;
        }
        return routes.stream()
                .filter(route -> StringUtils.hasText(route.getPathPattern()))
                .filter(route -> pathMatcher.match(route.getPathPattern(), uri))
                .findFirst()
                .orElse(null);
    }

    private void writeReject(HttpServletResponse response,
                             AgentGovernanceEntity route,
                             String reason) throws IOException {
        String body = "{\"success\":false,\"reason\":\"" + reason
                + "\",\"userMessage\":\"功能当前不可用\",\"retryable\":false}";
        boolean stream = route != null
                && route.getIsStream() != null
                && route.getIsStream();
        response.setCharacterEncoding("UTF-8");
        if (stream) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data: " + body + "\n\n");
        }
        else {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(body);
        }
        response.getWriter().flush();
    }
}
