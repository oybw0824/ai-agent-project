package com.nbcb.mcpclient.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import io.modelcontextprotocol.client.McpAsyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client、工具提供者和 ReactAgent 装配配置。
 */
@Slf4j
@Configuration
public class NativeMcpClientConfig {

    @Bean(destroyMethod = "close")
    public McpAsyncClient aiGatewayMcpClient(
            NativeMcpClientFactory factory,
            McpGatewayProperties properties) {
        return factory.create(properties);
    }

    @Bean("mcpAsyncToolCallbacks")
    public ToolCallbackProvider mcpAsyncToolCallbacks(
            McpAsyncClient aiGatewayMcpClient,
            McpGatewayProperties properties) {
        ApplicationScopedMcpToolSelector selector =
                new ApplicationScopedMcpToolSelector(
                        properties.applicationName());
        return AsyncMcpToolCallbackProvider.builder()
                .mcpClients(aiGatewayMcpClient)
                .toolFilter(selector)
                .toolNamePrefixGenerator(selector)
                .build();
    }

    @Bean
    public ReactAgent reactAgent(
            ChatModel chatModel,
            ToolCallbackProvider mcpAsyncToolCallbacks) {
        ToolCallback[] tools = mcpAsyncToolCallbacks.getToolCallbacks();
        log.info("ReactAgent 加载原生 MCP 工具完成，共 {} 个", tools.length);

        return ReactAgent.builder()
                .name("native-mcp-validation-agent")
                .description("用于验证原生 MCP Client 的智能助手")
                .model(chatModel)
                .systemPrompt("你是一个工具调用验证助手。用户的问题需要工具时，必须优先调用可用工具，并根据工具结果回答。")
                .tools(tools)
                .outputKey("agent_result")
                .enableLogging(true)
                .build();
    }
}
