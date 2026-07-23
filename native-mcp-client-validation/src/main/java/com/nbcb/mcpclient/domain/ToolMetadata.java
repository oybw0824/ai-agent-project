package com.nbcb.mcpclient.domain;

/**
 * MCP 工具元数据。
 */
public record ToolMetadata(
        String name,
        String title,
        String description,
        Object inputSchema) {
}
