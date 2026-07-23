package com.example.aigateway.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public final class GatewayMcpToolCallback implements ToolCallback {

    private final String applicationName;
    private final String gatewayToolName;
    private final ToolDefinition toolDefinition;
    private final GatewayMcpClient client;

    public GatewayMcpToolCallback(String applicationName, String gatewayToolName,
            String description, String inputSchema, GatewayMcpClient client) {
        this.applicationName = applicationName;
        this.gatewayToolName = gatewayToolName;
        this.client = client;
        this.toolDefinition = ToolDefinition.builder()
                .name(gatewayToolName)
                .description(description == null ? "" : description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String arguments) {
        return client.callTool(applicationName, gatewayToolName, arguments).block();
    }

    public String applicationName() {
        return applicationName;
    }

    public String gatewayToolName() {
        return gatewayToolName;
    }
}
