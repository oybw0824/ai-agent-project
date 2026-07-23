package com.nbcb.nacosmcpagent.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Agent chat request. agentId and nodeId select one exact runtime node.
 */
public record AgentChatRequest(
        @NotBlank(message = "agentId must not be blank") String agentId,
        @NotBlank(message = "nodeId must not be blank") String nodeId,
        @NotBlank(message = "question must not be blank") String question) {
}
