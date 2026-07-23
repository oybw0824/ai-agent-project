package com.nbcb.nacosmcpagent.config;

import com.nbcb.agent.governance.mcp.McpToolChannelContext;
import com.nbcb.agent.governance.mcp.McpToolChannelGovernanceManager;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.server.WebFilter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agent.runtime.enabled=false",
                "spring.ai.model.chat=none",
                "spring.ai.alibaba.mcp.nacos.register.enabled=false",
                "spring.ai.alibaba.mcp.nacos.client.enabled=false",
                "spring.ai.mcp.client.enabled=false",
                "spring.autoconfigure.exclude="
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpToolCallbackAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpStreamableClientAutoConfiguration,"
                        + "com.alibaba.cloud.ai.autoconfigure.mcp.discovery.client.NacosMcpSseClientAutoConfiguration"
        })
class AgentGovernanceStarterIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private List<McpServerFeatures.AsyncToolSpecification> localMcpTools;

    @Test
    void shouldLoadMcpToolChannelGovernanceBeansInWebFluxApplication() {
        assertThat(applicationContext.getBeansOfType(
                McpToolChannelGovernanceManager.class))
                .hasSize(1);
        assertThat(applicationContext.containsBean(
                "mcpToolSpecificationGovernancePostProcessor"))
                .isTrue();
        assertThat(applicationContext.containsBean(
                "mcpToolChannelContextWebFilter"))
                .isTrue();
        assertThat(applicationContext.getBean(
                "mcpToolChannelContextWebFilter"))
                .isInstanceOf(WebFilter.class);
    }

    @Test
    @Sql(statements = {
            "INSERT INTO AI_MCP_TOOL_CHANNEL_BLOCK "
                    + "(PK_ID, MCP_SERVER_NAME, TOOL_NAME, CHANNEL_CODE, "
                    + "STATUS, MESSAGE, IS_DELETE, EFFECTIVE_FROM, EFFECTIVE_TO) "
                    + "VALUES ('00000000000000000000000000009001', "
                    + "'nacos-mcp-agent', 'getWeatherByCity', 'CHANNEL_BLOCKED', "
                    + "'DISABLED', '渠道禁止调用天气工具', '0', NULL, NULL)"
    })
    void shouldBlockMcpToolWhenChannelIsDisabled() {
        McpServerFeatures.AsyncToolSpecification weatherTool =
                localMcpTools.stream()
                        .filter(specification -> "getWeatherByCity"
                                .equals(specification.tool().name()))
                        .findFirst()
                        .orElseThrow();
        McpAsyncServerExchange exchange = mock(McpAsyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(
                Map.of(McpToolChannelContext.CHANNEL_CONTEXT_KEY,
                        "CHANNEL_BLOCKED")));

        McpSchema.CallToolResult result =
                weatherTool.call().apply(exchange, Map.of("city", "北京")).block();

        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString())
                .contains("TOOL_UNAVAILABLE")
                .contains("getWeatherByCity")
                .contains("CHANNEL_BLOCKED")
                .contains("渠道禁止调用天气工具");
    }
}
