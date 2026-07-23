package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.config.TraceContextWebFilter;
import com.nbcb.nacosmcpagent.entity.AiCallAuditLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiCallAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
public class AgentCallAuditService {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger(
            "com.nbcb.nacosmcpagent.audit.AgentCallAuditLogger");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final AgentAuditProperties properties;
    private final AiCallAuditLogMapper mapper;

    public AgentCallAuditService(
            AgentAuditProperties properties,
            AiCallAuditLogMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public void record(AgentCallAuditEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        String traceId = MDC.get(TraceContextWebFilter.TRACE_ID_KEY);
        String spanId = MDC.get(TraceContextWebFilter.SPAN_ID_KEY);
        if (properties.isLogEnabled()) {
            logEvent(traceId, spanId, event);
        }
        if (properties.isDbEnabled()) {
            insertEvent(traceId, spanId, event);
        }
    }

    public ToolCallback wrapTool(
            ToolCallback callback,
            String agentId,
            String nodeId) {
        if (!properties.isEnabled()) {
            return callback;
        }
        return new AuditingToolCallback(callback, this, agentId, nodeId);
    }

    private void logEvent(
            String traceId,
            String spanId,
            AgentCallAuditEvent event) {
        AUDIT_LOGGER.info(
                "type={} traceId={} spanId={} agentId={} nodeId={} name={} success={} durationMs={} input=\"{}\" output=\"{}\" error=\"{}\"",
                event.callType(),
                value(traceId),
                value(spanId),
                value(event.agentId()),
                value(event.nodeId()),
                value(event.callName()),
                event.success(),
                event.durationMs(),
                singleLine(event.inputText()),
                singleLine(event.outputText()),
                singleLine(event.errorMessage()));
    }

    private void insertEvent(
            String traceId,
            String spanId,
            AgentCallAuditEvent event) {
        try {
            AiCallAuditLogEntity entity = new AiCallAuditLogEntity();
            entity.setPkId(UUID.randomUUID().toString().replace("-", ""));
            entity.setTraceId(traceId);
            entity.setSpanId(spanId);
            entity.setCallType(event.callType());
            entity.setAgentId(event.agentId());
            entity.setNodeId(event.nodeId());
            entity.setCallName(event.callName());
            entity.setInputText(event.inputText());
            entity.setOutputText(event.outputText());
            entity.setSuccess(event.success() ? "1" : "0");
            entity.setErrorMessage(event.errorMessage());
            entity.setDurationMs(event.durationMs());
            entity.setCreateTime(LocalDateTime.now().format(TIME_FORMATTER));
            mapper.insert(entity);
        }
        catch (RuntimeException ex) {
            log.warn("写入 Agent 调用审计表失败: type={}, name={}, cause={}",
                    event.callType(),
                    event.callName(),
                    rootMessage(ex));
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String singleLine(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
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
