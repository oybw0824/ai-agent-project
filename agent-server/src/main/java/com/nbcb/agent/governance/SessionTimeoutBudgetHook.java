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
 * 会话超时预算 Hook
 * <p>
 * 在每次模型调用前检查会话总耗时是否超过预算。
 * 首次调用时记录开始时间到内部 ConcurrentHashMap，后续调用检查剩余时间。
 * <p>
 * ★ 注意：state.data() 返回不可变 Map，因此使用外部 ConcurrentHashMap 存储会话状态。
 * <p>
 * ★ 终止条件：
 * <ul>
 *   <li>已耗时超过总预算 → 抛出 {@link AgentEarlyTerminationException}（REASON_TIMEOUT）</li>
 *   <li>剩余时间不足 {@code minimumNextStepMs} → 提前终止，避免执行不完整的推理步骤</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
public class SessionTimeoutBudgetHook extends ModelHook {

    /** ★ 外部存储：会话开始时间（key=sessionId, value=startTimeMs），解决 state.data() 不可变问题 */
    private static final ConcurrentHashMap<String, Long> SESSION_START_TIMES = new ConcurrentHashMap<>();

    private final AgentGovernanceProperties properties;
    private final AgentMetrics metrics;

    public SessionTimeoutBudgetHook(AgentGovernanceProperties properties, AgentMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "SessionTimeoutBudgetHook";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        String sessionId = config.threadId().orElse("unknown");

        // 首次调用：记录开始时间到外部 ConcurrentHashMap
        long startTime = SESSION_START_TIMES.computeIfAbsent(sessionId, k -> System.currentTimeMillis());

        long elapsed = System.currentTimeMillis() - startTime;
        long budgetMs = properties.getSessionTimeout().getDefaultBudgetMs();
        long minNextStepMs = properties.getSessionTimeout().getMinimumNextStepMs();

        // 检查是否超过总预算
        if (elapsed >= budgetMs) {
            log.warn("会话超时预算耗尽 [session={}]: elapsed={}ms, budget={}ms",
                    sessionId, elapsed, budgetMs);
            if (metrics != null) {
                metrics.governanceSessionTimeout.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_TIMEOUT,
                    "会话处理时间已达到上限（" + elapsed + "ms / " + budgetMs + "ms）",
                    sessionId);
        }

        // 检查剩余时间是否足够
        long remaining = budgetMs - elapsed;
        if (remaining < minNextStepMs) {
            log.warn("剩余处理时间不足 [session={}]: remaining={}ms, minNextStep={}ms",
                    sessionId, remaining, minNextStepMs);
            if (metrics != null) {
                metrics.governanceSessionTimeout.increment();
            }
            throw new AgentEarlyTerminationException(
                    AgentEarlyTerminationException.REASON_TIMEOUT,
                    "剩余处理时间不足（" + remaining + "ms），停止继续推理",
                    sessionId);
        }

        return CompletableFuture.completedFuture(
                Map.of("remainingTimeMillis", remaining));
    }
}
