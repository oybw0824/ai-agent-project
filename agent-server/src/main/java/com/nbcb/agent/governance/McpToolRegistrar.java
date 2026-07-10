package com.nbcb.agent.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP工具注册器
 * <p>
 * 在从Nacos拉取MCP工具注册信息时，结合本地yaml配置的工具状态进行过滤
 * 未启用的工具不注册给模型，模型自然看不到、也无法调用
 * <p>
 * ★ 第一层校验：注册拉取时过滤（源头过滤）
 * <p>
 * ★ 容错说明：
 * <ul>
 *   <li>当 Nacos 分布式客户端不可用时（{@code distributedTools == null}），优雅降级返回空列表</li>
 *   <li>yaml 未配置的工具默认按 DISABLED 处理，避免遗漏配置导致误放行</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
public class McpToolRegistrar {

    private final ToolGovernanceProperties governanceProperties;
    private final ToolCallbackProvider distributedTools;

    /**
     * 构造器（由 AgentConfig 中的 @Bean 方法调用）
     *
     * @param governanceProperties 工具治理配置属性
     * @param distributedTools     分布式工具提供者（Nacos 禁用时为 null）
     */
    public McpToolRegistrar(ToolGovernanceProperties governanceProperties,
                            ToolCallbackProvider distributedTools) {
        this.governanceProperties = governanceProperties;
        this.distributedTools = distributedTools;
    }

    /**
     * 加载可用的工具列表
     * <p>
     * 从分布式工具提供者获取所有工具，然后根据配置状态过滤。
     * 如果分布式客户端不可用，返回空列表优雅降级。
     *
     * @return 可用的工具回调列表
     */
    public List<ToolCallback> loadAvailableTools() {
        // ★ 容错：Nacos 分布式客户端不可用时优雅降级
        if (distributedTools == null) {
            log.warn("分布式工具客户端不可用（Nacos 已禁用或未连接），返回空工具列表");
            return new ArrayList<>();
        }

        ToolCallback[] allTools = distributedTools.getToolCallbacks();
        if (allTools == null || allTools.length == 0) {
            log.warn("未检测到MCP工具，返回空列表");
            return new ArrayList<>();
        }

        List<ToolCallback> availableTools = Arrays.stream(allTools)
                .filter(tool -> isEnabled(tool.getToolDefinition().name()))
                .collect(Collectors.toList());

        log.info("工具注册过滤结果：共 {} 个工具，启用 {} 个，禁用 {} 个",
                allTools.length, availableTools.size(), allTools.length - availableTools.size());

        return availableTools;
    }

    /**
     * 检查工具是否启用
     *
     * @param toolName 工具名称
     * @return true表示工具启用，false表示禁用
     */
    private boolean isEnabled(String toolName) {
        ToolGovernanceProperties.ToolPolicy policy = governanceProperties.getTools().get(toolName);
        // yaml未配置该工具时，policy为null，按未启用处理，避免遗漏配置导致误放行
        return policy != null && policy.getStatus() == ToolGovernanceProperties.ToolStatus.ENABLED;
    }
}
