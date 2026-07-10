package com.nbcb.agent.governance.interceptor;

import com.nbcb.agent.governance.config.AgentGovernanceManager;
import com.nbcb.agent.governance.entity.AgentGovernanceEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;

/**
 * ★ API 层拦截器 — HTTP 入口校验用户/机构权限 + Agent 开关
 * <p>
 * 校验逻辑（传了才校验，任一 DISABLED 即拦截）：
 * <ol>
 *   <li>提取请求头 X-User-Id / X-Org-Id（由上层认证网关注入）</li>
 *   <li>匹配路由规则获取 agentName</li>
 *   <li>userId 有值 → 查用户配置，DISABLED → 拦截</li>
 *   <li>orgId 有值 → 查机构配置，DISABLED → 拦截</li>
 *   <li>都通过 → 全局配置兜底</li>
 * </ol>
 * <p>
 * <b>未传 header 不拦截</b>：前端不一定会传 userId/orgId，传了才校验。
 * <p>
 * 部署说明：上游网关需剥离客户端传入的 X-User-Id / X-Org-Id 头，
 * 基于已验证的会话 Token 重新注入，确保不可被客户端伪造。
 *
 * @author com.nbcb
 */
@Slf4j
public class AgentGovernanceApiInterceptor implements HandlerInterceptor {

    private final AgentGovernanceManager governanceManager;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_ORG_ID = "X-Org-Id";

    public AgentGovernanceApiInterceptor(AgentGovernanceManager governanceManager) {
        this.governanceManager = governanceManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        // ★ 提取用户和机构信息（由网关注入，客户端不可直接设置）
        //    传了才校验：未传 header 时为 null，Manager 自动跳过对应维度
        String userId = request.getHeader(HEADER_USER_ID);
        String orgId = request.getHeader(HEADER_ORG_ID);

        // ★ 路径匹配
        AgentGovernanceEntity route = matchRoute(request.getRequestURI());
        if (route == null) {
            return true; // 未配置路由的接口不受管控
        }

        // ★ 查询配置（userId/orgId 独立校验，任一 DISABLED 即拦截）
        if (governanceManager.isEnabled(route.getAgentName(), userId, orgId)) {
            return true;
        }

        writeReject(response, route);
        log.warn("★ Agent 访问被拒绝 [{}] userId={} orgId={}",
                route.getAgentName(), userId, orgId);
        return false;
    }

    private AgentGovernanceEntity matchRoute(String uri) {
        List<AgentGovernanceEntity> routes = governanceManager.getRoutes();
        if (routes == null) return null;
        return routes.stream()
                .filter(r -> pathMatcher.match(r.getPathPattern(), uri))
                .findFirst()
                .orElse(null);
    }

    private void writeReject(HttpServletResponse response, AgentGovernanceEntity route)
            throws IOException {
        String body = "{\"success\":false,\"reason\":\"AGENT_DISABLED\",\"userMessage\":\"该功能当前已下线，暂不可用\"}";
        boolean isStream = route.getIsStream() != null && route.getIsStream();
        response.setCharacterEncoding("UTF-8");
        if (isStream) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("text/event-stream;charset=UTF-8");
            response.getWriter().write("data: " + body + "\n\n");
        } else {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(body);
        }
        response.getWriter().flush();
    }
}
