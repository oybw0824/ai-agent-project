package com.nbcb.agent.governance.mcp;

/**
 * MCP 工具渠道治理判定结果。
 */
public record McpToolChannelGovernanceDecision(
        boolean blocked,
        String reason,
        String message) {

    public static McpToolChannelGovernanceDecision allowed() {
        return new McpToolChannelGovernanceDecision(false, null, null);
    }

    public static McpToolChannelGovernanceDecision blocked(String message) {
        return new McpToolChannelGovernanceDecision(
                true,
                "TOOL_UNAVAILABLE",
                message);
    }
}
