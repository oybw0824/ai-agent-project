package com.nbcb.nacosmcpagent.audit;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public class McpToolLoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final McpToolCallLogService logService;
    private final String mcpServerName;
    private final String mcpEndpoint;

    public McpToolLoggingToolCallback(
            ToolCallback delegate,
            McpToolCallLogService logService,
            String mcpServerName,
            String mcpEndpoint) {
        this.delegate = delegate;
        this.logService = logService;
        this.mcpServerName = mcpServerName;
        this.mcpEndpoint = mcpEndpoint;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        long started = System.nanoTime();
        try {
            String output = delegate.call(toolInput);
            record(toolInput, output, true, null, started);
            return output;
        }
        catch (RuntimeException ex) {
            record(toolInput, null, false, ex.toString(), started);
            throw ex;
        }
    }

    @Override
    public String call(
            String toolInput,
            ToolContext toolContext) {
        long started = System.nanoTime();
        try {
            String output = delegate.call(toolInput, toolContext);
            record(toolInput, output, true, null, started);
            return output;
        }
        catch (RuntimeException ex) {
            record(toolInput, null, false, ex.toString(), started);
            throw ex;
        }
    }

    private void record(
            String toolInput,
            String toolOutput,
            boolean success,
            String errorMessage,
            long started) {
        logService.record(
                mcpServerName,
                mcpEndpoint,
                getToolDefinition().name(),
                toolInput,
                toolOutput,
                success,
                errorMessage,
                durationMs(started));
    }

    private static long durationMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
