package com.nbcb.agent.skill.dynamic;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 执行前仅注入当前内存快照，不访问数据库或 NAS。
 */
@HookPositions(HookPosition.BEFORE_AGENT)
public class DynamicSkillsAgentHook extends AgentHook {

    public static final String SNAPSHOT_STATE_KEY = "_dynamic_skill_snapshot";

    private final String runtimeAgentName;
    private final AgentSkillLocalStore localStore;
    private final SnapshotSkillsInterceptor modelInterceptor = new SnapshotSkillsInterceptor();
    private final ToolCallback readSkillTool = SnapshotReadSkillTool.createToolCallback();

    public DynamicSkillsAgentHook(String runtimeAgentName,
                                  AgentSkillLocalStore localStore) {
        this.runtimeAgentName = runtimeAgentName;
        this.localStore = localStore;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state,
                                                               RunnableConfig config) {
        try {
            AgentSkillSnapshot snapshot = localStore.requireReadySnapshot(runtimeAgentName);
            return CompletableFuture.completedFuture(Map.of(SNAPSHOT_STATE_KEY, snapshot));
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    @Override
    public List<ModelInterceptor> getModelInterceptors() {
        return List.of(modelInterceptor);
    }

    @Override
    public List<ToolCallback> getTools() {
        return List.of(readSkillTool);
    }

    @Override
    public Map<String, KeyStrategy> getKeyStrategys() {
        return Map.of(SNAPSHOT_STATE_KEY, KeyStrategy.REPLACE);
    }

    @Override
    public int getOrder() {
        return -1000;
    }

    @Override
    public String getName() {
        return "DynamicSkillsAgentHook";
    }
}
