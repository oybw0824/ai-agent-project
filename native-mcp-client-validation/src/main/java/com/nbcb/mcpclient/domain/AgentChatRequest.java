package com.nbcb.mcpclient.domain;

import jakarta.validation.constraints.NotBlank;

/**
 * Agent 对话请求。
 */
public record AgentChatRequest(
        @NotBlank(message = "question 不能为空") String question) {
}
