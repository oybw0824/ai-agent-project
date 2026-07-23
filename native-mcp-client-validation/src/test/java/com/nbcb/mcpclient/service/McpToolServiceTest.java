package com.nbcb.mcpclient.service;

import com.nbcb.mcpclient.config.McpGatewayProperties;
import com.nbcb.mcpclient.domain.ToolCallResponse;
import com.nbcb.mcpclient.exception.McpInvocationException;
import com.nbcb.mcpclient.exception.McpToolNotFoundException;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 工具目录和异常映射测试。
 */
@ExtendWith(MockitoExtension.class)
class McpToolServiceTest {

    @Mock
    private McpAsyncClient client;

    private McpToolService service;

    @BeforeEach
    void setUp() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("mcp-service___calculate")
                .description("计算数学表达式")
                .inputSchema(new McpSchema.JsonSchema(
                        "object",
                        Map.of("expression", Map.of("type", "string")),
                        List.of("expression"),
                        false,
                        null,
                        null))
                .build();
        McpSchema.Tool anotherApplicationTool = McpSchema.Tool.builder()
                .name("other-service___search")
                .description("其他应用工具")
                .inputSchema(new McpSchema.JsonSchema(
                        "object", Map.of(), List.of(), false,
                        null, null))
                .build();
        when(client.listTools()).thenReturn(Mono.just(
                new McpSchema.ListToolsResult(
                        List.of(tool, anotherApplicationTool), null)));
        service = new McpToolService(client, properties());
    }

    @Test
    void shouldRejectUnknownToolWithoutCallingGateway() {
        assertThat(service.listTools()).extracting(tool -> tool.name())
                .containsExactly("calculate");
        assertThatThrownBy(() -> service.callTool("missing", Map.of()))
                .isInstanceOf(McpToolNotFoundException.class);
        verify(client, never()).callTool(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldPreserveToolExecutionErrorResult() {
        when(client.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("表达式非法")), true)));

        ToolCallResponse response = service.callTool(
                "calculate", Map.of("expression", "1/0"));

        assertThat(response.error()).isTrue();
        assertThat(response.content()).hasSize(1);
        verify(client).callTool(org.mockito.ArgumentMatchers.argThat(request ->
                "mcp-service___calculate".equals(request.name())));
    }

    @Test
    void shouldWrapJsonRpcTopLevelError() {
        when(client.callTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new IllegalStateException("JSON-RPC error -32601")));

        assertThatThrownBy(() -> service.callTool("calculate", Map.of()))
                .isInstanceOf(McpInvocationException.class)
                .hasMessageContaining("calculate");
    }

    private McpGatewayProperties properties() {
        return new McpGatewayProperties(
                "http://localhost:11001",
                "/mcp",
                "mcp-service",
                true,
                "test-jwt",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }
}
