package com.nbcb.mcpclient.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具调用请求。
 */
public record ToolCallRequest(Map<String, Object> arguments) {

    public ToolCallRequest {
        arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
