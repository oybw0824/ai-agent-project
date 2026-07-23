package com.example.aigateway.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

public class GatewayMcpToolCallbackProvider implements ToolCallbackProvider, InitializingBean, DisposableBean {

    private final GatewayMcpProperties properties;
    private final GatewayMcpClient client;
    private volatile ToolCallback[] snapshot = new ToolCallback[0];
    private volatile Map<String, String> origins = Map.of();

    public GatewayMcpToolCallbackProvider(GatewayMcpProperties properties, GatewayMcpClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public void afterPropertiesSet() {
        properties.validateUniqueApplications();
        client.initialize().block();

        List<ToolCallback> callbacks = new ArrayList<>();
        Map<String, String> loadedOrigins = new LinkedHashMap<>();
        for (GatewayMcpProperties.Application application : properties.applications()) {
            List<GatewayMcpTool> tools = client.listTools(application.name()).block();
            if (tools == null) {
                throw new GatewayMcpException("tools/list returned no result for " + application.name());
            }
            for (GatewayMcpTool tool : tools) {
                String toolName = tool.name();
                String previous = loadedOrigins.putIfAbsent(toolName,
                        "AI gateway application '" + application.name() + "' (tool '" + tool.name() + "')");
                if (previous != null) {
                    throw new IllegalStateException("Tool name conflict: '" + toolName + "' comes from both "
                            + previous + " and AI gateway application '" + application.name()
                            + "'. Tool names returned by the AI gateway must be unique.");
                }
                callbacks.add(new GatewayMcpToolCallback(application.name(), tool.name(),
                        tool.description(), tool.inputSchema().toString(), client));
            }
        }
        this.origins = Map.copyOf(loadedOrigins);
        this.snapshot = callbacks.toArray(ToolCallback[]::new);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return snapshot.clone();
    }

    public Map<String, String> origins() {
        return origins;
    }

    @Override
    public void destroy() {
        client.close().block();
    }
}
