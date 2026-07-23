package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.config.TraceContextWebFilter;
import com.nbcb.nacosmcpagent.entity.AiMcpToolCallLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiMcpToolCallLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolCallLogServiceTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPersistMcpToolCallWithTraceHeaders() {
        AiMcpToolCallLogMapper mapper = mock(AiMcpToolCallLogMapper.class);
        McpToolCallLogService service = new McpToolCallLogService(
                properties(true), mapper);
        MDC.put(TraceContextWebFilter.TRACE_ID_KEY, "trace-a");
        MDC.put(TraceContextWebFilter.SPAN_ID_KEY, "span-a");

        service.record(
                "nacos-mcp-agent",
                "/mcp",
                "getWeatherByCity",
                "{\"city\":\"北京\"}",
                "{\"temperature\":26}",
                true,
                null,
                12);

        ArgumentCaptor<AiMcpToolCallLogEntity> captor =
                ArgumentCaptor.forClass(AiMcpToolCallLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-a");
        assertThat(captor.getValue().getSpanId()).isEqualTo("span-a");
        assertThat(captor.getValue().getMcpServerName())
                .isEqualTo("nacos-mcp-agent");
        assertThat(captor.getValue().getMcpEndpoint()).isEqualTo("/mcp");
        assertThat(captor.getValue().getToolName())
                .isEqualTo("getWeatherByCity");
        assertThat(captor.getValue().getToolInput())
                .isEqualTo("{\"city\":\"北京\"}");
        assertThat(captor.getValue().getToolOutput())
                .isEqualTo("{\"temperature\":26}");
        assertThat(captor.getValue().getSuccess()).isEqualTo("1");
    }

    @Test
    void shouldIgnoreDatabaseFailure() {
        AiMcpToolCallLogMapper mapper = mock(AiMcpToolCallLogMapper.class);
        when(mapper.insert(any(AiMcpToolCallLogEntity.class)))
                .thenThrow(new IllegalStateException("db down"));
        McpToolCallLogService service = new McpToolCallLogService(
                properties(true), mapper);

        assertThatCode(() -> service.record(
                "nacos-mcp-agent",
                "/mcp",
                "getWeatherByCity",
                "input",
                "output",
                true,
                null,
                8)).doesNotThrowAnyException();
    }

    @Test
    void shouldSkipWhenMcpToolAuditDisabled() {
        AiMcpToolCallLogMapper mapper = mock(AiMcpToolCallLogMapper.class);
        McpToolCallLogService service = new McpToolCallLogService(
                properties(false), mapper);

        service.record(
                "nacos-mcp-agent",
                "/mcp",
                "getWeatherByCity",
                "input",
                "output",
                true,
                null,
                8);

        verify(mapper, never()).insert(any(AiMcpToolCallLogEntity.class));
    }

    private static AgentAuditProperties properties(boolean mcpToolEnabled) {
        AgentAuditProperties properties = new AgentAuditProperties();
        properties.setLogEnabled(false);
        properties.setDbEnabled(true);
        properties.setMcpToolEnabled(mcpToolEnabled);
        properties.setMcpToolDbEnabled(true);
        return properties;
    }
}
