package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.config.TraceContextWebFilter;
import com.nbcb.nacosmcpagent.entity.AiMcpToolCallLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiMcpToolCallLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
public class McpToolCallLogService {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AgentAuditProperties properties;
    private final AiMcpToolCallLogMapper mapper;

    public McpToolCallLogService(
            AgentAuditProperties properties,
            AiMcpToolCallLogMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public ToolCallback wrapTool(
            ToolCallback callback,
            String mcpServerName,
            String mcpEndpoint) {
        if (!isEnabled()) {
            return callback;
        }
        return new McpToolLoggingToolCallback(
                callback,
                this,
                mcpServerName,
                mcpEndpoint);
    }

    void record(
            String mcpServerName,
            String mcpEndpoint,
            String toolName,
            String toolInput,
            String toolOutput,
            boolean success,
            String errorMessage,
            long durationMs) {
        if (!isEnabled()) {
            return;
        }
        try {
            AiMcpToolCallLogEntity entity = new AiMcpToolCallLogEntity();
            entity.setPkId(UUID.randomUUID().toString().replace("-", ""));
            entity.setTraceId(MDC.get(TraceContextWebFilter.TRACE_ID_KEY));
            entity.setSpanId(MDC.get(TraceContextWebFilter.SPAN_ID_KEY));
            entity.setMcpServerName(mcpServerName);
            entity.setMcpEndpoint(mcpEndpoint);
            entity.setToolName(toolName);
            entity.setToolInput(toolInput);
            entity.setToolOutput(toolOutput);
            entity.setSuccess(success ? "1" : "0");
            entity.setErrorMessage(errorMessage);
            entity.setDurationMs(durationMs);
            entity.setCreateTime(LocalDateTime.now().format(TIME_FORMATTER));
            mapper.insert(entity);
        }
        catch (RuntimeException ex) {
            log.warn("写入 MCP 工具调用记录表失败: toolName={}, cause={}",
                    toolName,
                    rootMessage(ex));
        }
    }

    private boolean isEnabled() {
        return properties.isEnabled()
                && properties.isMcpToolEnabled()
                && properties.isMcpToolDbEnabled();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
