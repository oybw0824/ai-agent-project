package com.nbcb.agent.governance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具治理拦截器包装器
 * <p>
 * 包装真实的 {@link ToolCallback}，在调用前应用工具治理拦截逻辑。
 * 实现 {@code org.springframework.ai.tool.ToolCallback} 接口，
 * 与 LoggingToolCallback、ReactAgent 使用相同的类型体系。
 * <p>
 * ★ 工作流程：
 * <ol>
 *   <li>获取工具名 → 调用 {@link ToolGovernanceInterceptor#checkEnabled(String)} 进行第二层校验</li>
 *   <li>校验通过 → 委托给真实工具执行</li>
 *   <li>校验失败 → {@code checkEnabled()} 抛出 {@code ToolExecutionException}，
 *       由 Agent 框架的 DefaultToolExecutionExceptionProcessor 处理</li>
 * </ol>
 *
 * @author com.nbcb
 */
@Slf4j
@RequiredArgsConstructor
public class ToolGovernanceWrapper implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolGovernanceInterceptor interceptor;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // ★ 第二层防御：调用前再次校验工具状态
        // 校验失败时 checkEnabled() 抛出 ToolExecutionException，由框架处理
        interceptor.checkEnabled(delegate.getToolDefinition().name());

        // 校验通过，委托给真实工具执行
        return delegate.call(toolInput, toolContext);
    }
}
