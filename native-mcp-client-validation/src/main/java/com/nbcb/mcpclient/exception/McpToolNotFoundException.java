package com.nbcb.mcpclient.exception;

/**
 * MCP 工具不存在异常。
 */
public class McpToolNotFoundException extends RuntimeException {

    public McpToolNotFoundException(String toolName) {
        super("MCP 工具不存在：" + toolName);
    }
}
