package com.nbcb.mcpclient.service;

import com.nbcb.mcpclient.config.McpGatewayProperties;
import com.nbcb.mcpclient.domain.ToolCallResponse;
import com.nbcb.mcpclient.domain.ToolMetadata;
import com.nbcb.mcpclient.exception.McpInvocationException;
import com.nbcb.mcpclient.exception.McpToolNotFoundException;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 原生 MCP 工具目录及直接调用服务。
 */
@Slf4j
@Service
public class McpToolService {

    private final McpAsyncClient client;
    private final McpGatewayProperties properties;
    private final List<ToolMetadata> tools;
    private final Map<String, McpSchema.Tool> toolsByName;

    public McpToolService(
            McpAsyncClient client,
            McpGatewayProperties properties) {
        this.client = client;
        this.properties = properties;

        List<McpSchema.Tool> discoveredTools = loadAllTools();
        Map<String, McpSchema.Tool> indexedTools = new LinkedHashMap<>();
        List<ToolMetadata> metadata = new ArrayList<>();
        String prefix = properties.applicationName() + "___";
        for (McpSchema.Tool tool : discoveredTools) {
            if (!tool.name().startsWith(prefix)) {
                continue;
            }
            String shortName = tool.name().substring(prefix.length());
            McpSchema.Tool previous = indexedTools.putIfAbsent(shortName, tool);
            if (previous != null) {
                throw new IllegalStateException(
                        "AI 网关工具短名称冲突：" + shortName);
            }
            metadata.add(new ToolMetadata(
                    shortName,
                    tool.title(),
                    tool.description(),
                    tool.inputSchema()));
        }
        this.toolsByName = Collections.unmodifiableMap(indexedTools);
        this.tools = List.copyOf(metadata);
        log.info("启动自检完成：从 AI 网关获取到 {} 个 MCP 工具", tools.size());
    }

    public List<ToolMetadata> listTools() {
        return tools;
    }

    public ToolCallResponse callTool(
            String toolName,
            Map<String, Object> arguments) {
        McpSchema.Tool gatewayTool = toolsByName.get(toolName);
        if (gatewayTool == null) {
            throw new McpToolNotFoundException(toolName);
        }

        try {
            McpSchema.CallToolResult result = client.callTool(
                            new McpSchema.CallToolRequest(
                                    gatewayTool.name(), arguments))
                    .block(properties.requestTimeout());
            if (result == null) {
                throw new IllegalStateException("工具调用返回空结果");
            }
            return new ToolCallResponse(
                    toolName,
                    Boolean.TRUE.equals(result.isError()),
                    result.content(),
                    result.structuredContent());
        }
        catch (RuntimeException ex) {
            throw new McpInvocationException("MCP 工具调用失败：" + toolName, ex);
        }
    }

    private List<McpSchema.Tool> loadAllTools() {
        try {
            List<McpSchema.Tool> allTools = new ArrayList<>();
            McpSchema.ListToolsResult page = client.listTools()
                    .block(properties.requestTimeout());
            while (page != null) {
                if (page.tools() != null) {
                    allTools.addAll(page.tools());
                }
                if (page.nextCursor() == null || page.nextCursor().isBlank()) {
                    break;
                }
                page = client.listTools(page.nextCursor())
                        .block(properties.requestTimeout());
            }
            return allTools;
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("启动自检失败：无法获取 MCP 工具列表", ex);
        }
    }
}
