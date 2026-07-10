package com.nbcb.agent.governance;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ★ 工具事件 Hook — 通过框架 {@link Hook#getToolInterceptors()} 机制注册工具拦截器
 * <p>
 * 框架的 AgentToolNode 在初始化时会收集所有 Hook 的 ToolInterceptor，
 * 在工具调用前后链式执行。此 Hook 唯一目的是贡献 {@link ToolEventInterceptor}。
 * <p>
 * ★ 为什么需要此 Hook：
 * <ul>
 *   <li>LoggingToolCallback（ToolCallback 包装）不被框架调用 — 框架通过 AsyncToolCallbackAdapter 异步执行</li>
 *   <li>ToolInterceptor 是框架官方拦截机制，100% 覆盖所有工具调用</li>
 *   <li>通过 Hook.getToolInterceptors() 注册是框架推荐方式</li>
 * </ul>
 * <p>
 * ★ 继承 ModelHook 而非直接实现 Hook，以复用 setAgentName/getAgentName/setAgent/getAgent 的默认实现。
 *
 * @author com.nbcb
 */
public class ToolEventHook extends ModelHook {

    private final ToolEventInterceptor interceptor;

    public ToolEventHook() {
        this.interceptor = new ToolEventInterceptor();
    }

    @Override
    public String getName() {
        return "ToolEventHook";
    }

    /**
     * ★ 不参与模型调用拦截，仅贡献工具拦截器
     */
    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * ★ 不参与模型调用拦截，仅贡献工具拦截器
     */
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * ★ 贡献工具拦截器列表 — 框架在 AgentToolNode 初始化时调用
     *
     * @return 包含 ToolEventInterceptor 的列表
     */
    @Override
    public List<ToolInterceptor> getToolInterceptors() {
        return List.of(interceptor);
    }
}
