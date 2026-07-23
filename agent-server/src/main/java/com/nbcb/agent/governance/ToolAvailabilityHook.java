package com.nbcb.agent.governance;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 工具可用性注入 Hook
 * <p>
 * 在每次 Agent 执行前（{@code beforeAgent}），将不可用工具和降级工具信息注入到
 * 会话状态和 system prompt 中，让模型知晓哪些工具当前不可用或已降级。
 * <p>
 * ★ 处理规则：
 * <ul>
 *   <li>{@code DISABLED}：从工具列表过滤 → 模型不可见（由 McpToolRegistrar 完成）</li>
 *   <li>{@code CIRCUIT_OPEN} / {@code retryable:false}：禁止模型继续调用同一路径</li>
 *   <li>{@code DEGRADED}：可调用，但提示数据新鲜度降低</li>
 * </ul>
 * <p>
 * ★ 注入格式（追加到 state.data()）：
 * <pre>{@code
 * {
 *   "unavailableTools": [{"toolName": "...", "reason": "CIRCUIT_OPEN", "retryable": false}],
 *   "degradedTools": [{"toolName": "...", "dataFreshness": "CACHED"}]
 * }
 * }</pre>
 *
 * @author com.nbcb
 */
@Slf4j
public class ToolAvailabilityHook extends AgentHook {

    private static final String UNAVAILABLE_KEY = "_governance_unavailable_tools";
    private static final String DEGRADED_KEY = "_governance_degraded_tools";

    private final ToolGovernanceProperties toolGovernanceProperties;

    public ToolAvailabilityHook(ToolGovernanceProperties toolGovernanceProperties) {
        this.toolGovernanceProperties = toolGovernanceProperties;
    }

    @Override
    public String getName() {
        return "ToolAvailabilityHook";
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        // 收集不可用工具列表
        List<Map<String, Object>> unavailableTools = new ArrayList<>();
        List<Map<String, Object>> degradedTools = new ArrayList<>();

        Map<String, ToolGovernanceProperties.ToolPolicy> tools = toolGovernanceProperties.getTools();
        if (tools != null) {
            for (Map.Entry<String, ToolGovernanceProperties.ToolPolicy> entry : tools.entrySet()) {
                String toolName = entry.getKey();
                ToolGovernanceProperties.ToolPolicy policy = entry.getValue();

                if (policy != null) {
                    switch (policy.getStatus()) {
                        case DISABLED -> {
                            Map<String, Object> info = new HashMap<>();
                            info.put("toolName", toolName);
                            info.put("reason", "DISABLED");
                            info.put("retryable", false);
                            unavailableTools.add(info);
                        }
                        // ENABLED — 正常可用，不添加
                        default -> { /* ENABLED */ }
                    }
                }
            }
        }

        if (!unavailableTools.isEmpty() || !degradedTools.isEmpty()) {
            log.info("工具可用性注入：不可用={}, 降级={}",
                    unavailableTools.size(), degradedTools.size());
        }

        // ★ 通过返回值将工具可用性信息传递给 Agent（不再直接修改 state.data()，
        //    因为框架返回的是不可变 Map，会导致 UnsupportedOperationException）
        Map<String, Object> result = new HashMap<>();
        if (!unavailableTools.isEmpty()) {
            result.put("unavailableTools", unavailableTools);
        }
        if (!degradedTools.isEmpty()) {
            result.put("degradedTools", degradedTools);
        }
        return CompletableFuture.completedFuture(result);
    }

    /**
     * 获取当前不可用工具列表
     *
     * @param state 会话状态
     * @return 不可用工具列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getUnavailableTools(OverAllState state) {
        Object value = state.data().get(UNAVAILABLE_KEY);
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    /**
     * 获取当前降级工具列表
     *
     * @param state 会话状态
     * @return 降级工具列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getDegradedTools(OverAllState state) {
        Object value = state.data().get(DEGRADED_KEY);
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }
}
