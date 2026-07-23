package com.nbcb.nacosmcpagent.audit;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public class AuditingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentCallAuditService auditService;
    private final String agentId;
    private final String nodeId;

    public AuditingToolCallback(
            ToolCallback delegate,
            AgentCallAuditService auditService,
            String agentId,
            String nodeId) {
        this.delegate = delegate;
        this.auditService = auditService;
        this.agentId = agentId;
        this.nodeId = nodeId;
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
            auditService.record(AgentCallAuditEvent.tool(
                    agentId,
                    nodeId,
                    getToolDefinition().name(),
                    toolInput,
                    output,
                    true,
                    null,
                    durationMs(started)));
            return output;
        }
        catch (RuntimeException ex) {
            auditService.record(AgentCallAuditEvent.tool(
                    agentId,
                    nodeId,
                    getToolDefinition().name(),
                    toolInput,
                    null,
                    false,
                    ex.toString(),
                    durationMs(started)));
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
            auditService.record(AgentCallAuditEvent.tool(
                    agentId,
                    nodeId,
                    getToolDefinition().name(),
                    toolInput,
                    output,
                    true,
                    null,
                    durationMs(started)));
            return output;
        }
        catch (RuntimeException ex) {
            auditService.record(AgentCallAuditEvent.tool(
                    agentId,
                    nodeId,
                    getToolDefinition().name(),
                    toolInput,
                    null,
                    false,
                    ex.toString(),
                    durationMs(started)));
            throw ex;
        }
    }

    private static long durationMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
