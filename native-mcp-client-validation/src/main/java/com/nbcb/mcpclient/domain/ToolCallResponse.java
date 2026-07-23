package com.nbcb.mcpclient.domain;

import java.util.List;

/**
 * MCP 工具调用结果。
 */
public record ToolCallResponse(
        String toolName,
        boolean error,
        List<?> content,
        Object structuredContent) {
}
