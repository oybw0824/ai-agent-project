package com.nbcb.mcpclient.config;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 网关应用工具筛选和短名称映射测试。
 */
class ApplicationScopedMcpToolSelectorTest {

    @Test
    void shouldFilterByApplicationAndExposeShortName() {
        ApplicationScopedMcpToolSelector selector =
                new ApplicationScopedMcpToolSelector("mcp-service");
        McpSchema.Tool target = tool("mcp-service___calculate");
        McpSchema.Tool anotherApplication =
                tool("other-service___search");

        assertThat(selector.test(null, target)).isTrue();
        assertThat(selector.prefixedToolName(null, target))
                .isEqualTo("calculate");
        assertThat(selector.test(null, anotherApplication)).isFalse();
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder()
                .name(name)
                .description("测试工具")
                .inputSchema(new McpSchema.JsonSchema(
                        "object", null, null, null, null, null))
                .build();
    }
}
