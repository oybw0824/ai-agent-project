package com.nbcb.agent.governance.aop;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.nbcb.agent.governance.config.AgentGovernanceManager;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ Agent 层 AOP 拦截器 — 兜底拦截所有 ReactAgent 调用
 * <p>
 * 覆盖不经过 HTTP 入口的场景（定时任务、MQ 消费、内部 RPC 调用）。
 * 分别处理同步 invoke() 和流式 stream() 两种返回类型。
 *
 * @author com.nbcb
 */
@Slf4j
@Aspect
public class AgentGovernanceAopInterceptor {

    private final AgentGovernanceManager governanceManager;

    public AgentGovernanceAopInterceptor(AgentGovernanceManager governanceManager) {
        this.governanceManager = governanceManager;
    }

    /** 同步入口 — 返回普通对象 */
    @Around("execution(* com.alibaba.cloud.ai.graph.agent.ReactAgent.invoke(..))")
    public Object gateSync(ProceedingJoinPoint pjp) throws Throwable {
        String agentName = resolveAgentName(pjp.getTarget());
        if (!governanceManager.isEnabled(agentName)) {
            log.warn("★ Agent AOP 同步拦截 [{}] — 已禁用", agentName);
            return buildRejectedResponse();
        }
        return pjp.proceed();
    }

    /** 流式入口 — Agent 被禁用时返回 Flux.error，保持响应式语义 */
    @Around("execution(* com.alibaba.cloud.ai.graph.agent.ReactAgent.stream(..))")
    public Object gateReactive(ProceedingJoinPoint pjp) throws Throwable {
        String agentName = resolveAgentName(pjp.getTarget());
        if (!governanceManager.isEnabled(agentName)) {
            log.warn("★ Agent AOP 流式拦截 [{}] — 已禁用", agentName);
            return Flux.error(new RuntimeException("Agent [" + agentName + "] 已被禁用"));
        }
        return pjp.proceed();
    }

    private String resolveAgentName(Object agentInstance) {
        try {
            return ((ReactAgent) agentInstance).name();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildRejectedResponse() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", false);
        map.put("reason", "AGENT_DISABLED");
        map.put("userMessage", "该功能当前已下线，暂不可用");
        return map;
    }
}
