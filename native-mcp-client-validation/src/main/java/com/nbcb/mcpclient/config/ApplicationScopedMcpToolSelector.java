package com.nbcb.mcpclient.config;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;

/**
 * 在创建 Spring AI 工具回调前筛选指定应用，并生成 Agent 使用的短名称。
 *
 * <p>筛选阶段读取 MCP 返回的原始工具名，避免 Spring AI 格式化名称后丢失
 * {@code 应用名___工具名} 边界。回调内部仍保留原始工具对象，因此调用网关时
 * 会发送完整名称。</p>
 */
@Slf4j
final class ApplicationScopedMcpToolSelector
        implements McpToolFilter, McpToolNamePrefixGenerator {

    private static final String SEPARATOR = "___";

    private final String prefix;

    ApplicationScopedMcpToolSelector(String applicationName) {
        this.prefix = applicationName + SEPARATOR;
    }

    @Override
    public boolean test(
            McpConnectionInfo connectionInfo,
            McpSchema.Tool tool) {
        return tool.name().startsWith(prefix);
    }

    @Override
    public String prefixedToolName(
            McpConnectionInfo connectionInfo,
            McpSchema.Tool tool) {
        if (!test(connectionInfo, tool)) {
            throw new IllegalArgumentException(
                    "工具不属于当前 MCP 应用：" + tool.name());
        }
        String shortName = tool.name().substring(prefix.length());
        log.info("AI 网关工具名称映射：{} → {}",
                tool.name(), shortName);
        return shortName;
    }
}
