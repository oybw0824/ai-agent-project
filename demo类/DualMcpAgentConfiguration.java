package com.example.dualmcp.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.example.aigateway.mcp.GatewayMcpToolCallbackProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DualMcpAgentConfiguration {

    @Bean
    SkillRegistry skillRegistry() {
        return ClasspathSkillRegistry.builder().classpathPath("skills").build();
    }

    @Bean
    SkillsAgentHook skillsAgentHook(SkillRegistry skillRegistry) {
        return SkillsAgentHook.builder().skillRegistry(skillRegistry).build();
    }

    @Bean("allMcpToolCallbacks")
    ToolCallbackProvider allMcpToolCallbacks(
            @Qualifier("distributedAsyncToolCallback") ToolCallbackProvider nativeMcpTools,
            @Qualifier("gatewayMcpToolCallbacks") GatewayMcpToolCallbackProvider gatewayMcpTools) {
        return mergeAndValidate(nativeMcpTools, gatewayMcpTools);
    }

    static ToolCallbackProvider mergeAndValidate(ToolCallbackProvider nativeMcpTools,
            GatewayMcpToolCallbackProvider gatewayMcpTools) {
        List<ToolCallback> merged = new ArrayList<>();
        Map<String, String> origins = new LinkedHashMap<>();

        addTools(merged, origins, nativeMcpTools.getToolCallbacks(), "native MCP");
        for (ToolCallback callback : gatewayMcpTools.getToolCallbacks()) {
            String name = callback.getToolDefinition().name();
            String gatewayOrigin = gatewayMcpTools.origins().getOrDefault(name, "AI gateway MCP");
            addTool(merged, origins, callback, gatewayOrigin);
        }
        ToolCallback[] immutableSnapshot = merged.toArray(ToolCallback[]::new);
        return () -> immutableSnapshot.clone();
    }

    private static void addTools(List<ToolCallback> merged, Map<String, String> origins,
            ToolCallback[] tools, String source) {
        for (ToolCallback tool : tools) {
            addTool(merged, origins, tool, source);
        }
    }

    private static void addTool(List<ToolCallback> merged, Map<String, String> origins,
            ToolCallback tool, String source) {
        String name = tool.getToolDefinition().name();
        String previous = origins.putIfAbsent(name, source);
        if (previous != null) {
            throw new IllegalStateException("Tool name conflict: '" + name + "' comes from both " + previous
                    + " and " + source + ". Tool names returned by MCP servers must be unique.");
        }
        merged.add(tool);
    }

    @Bean
    ReactAgent dualMcpReactAgent(ChatModel chatModel,
            @Qualifier("allMcpToolCallbacks") ToolCallbackProvider allMcpTools,
            SkillsAgentHook skillsAgentHook) {
        return ReactAgent.builder()
                .name("dual-mcp-agent")
                .description("同时支持原生 MCP 和 AI 网关存量 MCP 的智能助手")
                .model(chatModel)
                .systemPrompt("""
                        你是一个能够调用多种工具的智能助手。

                        工具可能来自原生 MCP Server、AI 网关封装的存量 REST API，或本地 Java Tool。
                        根据用户问题选择合适的工具，必须严格按照工具 inputSchema 生成参数，
                        不要调用与任务无关的工具。需要技能详情时使用 read_skill。
                        """)
                .toolCallbackProviders(allMcpTools)
                .hooks(List.of(skillsAgentHook))
                .outputKey("agent-result")
                .enableLogging(true)
                .build();
    }
}
