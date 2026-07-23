package com.nbcb.mcpclient.exception;

/**
 * MCP 网关调用异常。
 */
public class McpInvocationException extends RuntimeException {

    public McpInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
