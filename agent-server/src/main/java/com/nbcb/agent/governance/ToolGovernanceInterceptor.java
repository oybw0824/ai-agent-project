package com.nbcb.agent.governance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * 工具治理拦截器
 * <p>
 * 在工具调用前进行第二层校验，兜底拒绝已下线的工具。
 * 即使工具列表未及时刷新、或并发会话仍持有旧工具列表，调用时也必须再次读取配置检查状态并拒绝。
 * <p>
 * ★ 设计说明：
 * <ul>
 *   <li>不实现框架拦截器接口（Spring AI 框架中不存在 ToolInterceptor API）</li>
 *   <li>通过 {@link #checkEnabled(String)} 抛出 {@link ToolExecutionException} 来拒绝调用</li>
 *   <li>{@code ToolExecutionException} 由 Agent 框架的 {@code DefaultToolExecutionExceptionProcessor} 处理，
 *       正确地向 LLM 报告工具调用失败</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@RequiredArgsConstructor
public class ToolGovernanceInterceptor {

    private final ToolGovernanceProperties governanceProperties;

    /**
     * 检查工具是否被允许调用
     *
     * @param toolName 工具名称
     * @return true 表示工具已启用，允许调用
     */
    public boolean isEnabled(String toolName) {
        ToolGovernanceProperties.ToolPolicy policy = governanceProperties.getTools().get(toolName);
        // yaml 未显式配置的工具，默认按 DISABLED 处理（安全默认值，避免遗漏配置导致误放行）
        return policy != null && policy.getStatus() == ToolGovernanceProperties.ToolStatus.ENABLED;
    }

    /**
     * 校验工具状态，未启用时抛出 ToolExecutionException
     * <p>
     * ★ 第二层防御：即使第一层（注册时过滤）被绕过，调用时也必须再次检查。
     * <p>
     * 抛出 {@link ToolExecutionException} 而非返回魔术字符串：
     * <ul>
     *   <li>符合 Spring AI 框架约定</li>
     *   <li>Agent 框架的 DefaultToolExecutionExceptionProcessor 自动处理</li>
     *   <li>LLM 能正确感知工具调用失败并做出合理响应</li>
     * </ul>
     *
     * @param toolName 工具名称
     * @throws ToolExecutionException 当工具被禁用或未配置时抛出
     */
    public void checkEnabled(String toolName) {
        if (!isEnabled(toolName)) {
            log.warn("工具调用被拒绝 - [{}] 已被禁用或未配置", toolName);
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(toolName)
                    .description("工具已下线，暂不可用")
                    .inputSchema("{}")
                    .build();
            throw new ToolExecutionException(def,
                    new RuntimeException("工具[" + toolName + "]已下线，暂不可用"));
        }
    }
}
