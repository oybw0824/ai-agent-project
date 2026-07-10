package com.nbcb.agent.governance;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.nbcb.agent.exception.AgentEarlyTerminationException;
import com.nbcb.agent.metric.AgentMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型调用次数限制 Hook
 * <p>
 * 在每次模型调用前递增计数器，超过限制时终止会话。
 * 计数器存储在内部 ConcurrentHashMap 中，同一会话内所有 Hook 实例共享。
 * <p>
 * ★ 注意：state.data() 返回不可变 Map，因此使用外部 ConcurrentHashMap 存储会话状态。
 * <p>
 * ★ 设计说明：
 * <ul>
 *   <li>计数器 key 为 sessionId</li>
 *   <li>限制值从 {@link AgentGovernanceProperties} 读取</li>
 *   <li>当前使用默认限制（高风险等级后续扩展）</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
public class ModelCallLimitHook extends ModelHook {

    /** ★ 外部存储：会话调用计数（key=sessionId, value=count），解决 state.data() 不可变问题 */
    private static final ConcurrentHashMap<String, Integer> SESSION_CALL_COUNTS = new ConcurrentHashMap<>();

    private final AgentGovernanceProperties properties;
    private final AgentMetrics metrics;

    public ModelCallLimitHook(AgentGovernanceProperties properties, AgentMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "ModelCallLimitHook";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        String sessionId = config.threadId().orElse("unknown");

        // ★ 使用 compute + atomic 递增，避免多线程竞态
        int count = SESSION_CALL_COUNTS.compute(sessionId, (k, v) -> v == null ? 1 : v + 1);

        int limit = properties.getModelCallLimit().getDefaultLimit();

        if (count > limit) {
            log.warn("模型调用次数超限 [session={}]: used={}, limit={}",
                    sessionId, count, limit);
            if (metrics != null) {
                metrics.governanceModelCallLimit.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_MODEL_CALL_LIMIT,
                    "模型调用次数超过限制（" + count + " / " + limit + "）",
                    sessionId);
        }

        log.debug("模型调用计数 [session={}]: {}/{}", sessionId, count, limit);
        return CompletableFuture.completedFuture(
                Map.of("modelCallUsed", count, "modelCallLimit", limit));
    }
}
