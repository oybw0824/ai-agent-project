package com.nbcb.nacosmcpagent.audit;

public record AgentCallAuditEvent(
        String callType,
        String agentId,
        String nodeId,
        String callName,
        String inputText,
        String outputText,
        boolean success,
        String errorMessage,
        long durationMs) {

    public static AgentCallAuditEvent model(
            String agentId,
            String nodeId,
            String modelId,
            String inputText,
            String outputText,
            boolean success,
            String errorMessage,
            long durationMs) {
        return new AgentCallAuditEvent(
                "MODEL",
                agentId,
                nodeId,
                modelId,
                inputText,
                outputText,
                success,
                errorMessage,
                durationMs);
    }

    public static AgentCallAuditEvent tool(
            String agentId,
            String nodeId,
            String toolName,
            String inputText,
            String outputText,
            boolean success,
            String errorMessage,
            long durationMs) {
        return new AgentCallAuditEvent(
                "TOOL",
                agentId,
                nodeId,
                toolName,
                inputText,
                outputText,
                success,
                errorMessage,
                durationMs);
    }
}
