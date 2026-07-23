package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.entity.AiMcpToolCallLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiMcpToolCallLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpToolLoggingToolCallbackTest {

    @Test
    void shouldRecordMcpToolInputAndOutput() {
        AiMcpToolCallLogMapper mapper = mock(AiMcpToolCallLogMapper.class);
        ToolCallback callback = new McpToolLoggingToolCallback(
                new FixedToolCallback(false),
                logService(mapper),
                "nacos-mcp-agent",
                "/mcp");

        String output = callback.call("{\"city\":\"北京\"}");

        assertThat(output).isEqualTo("{\"temperature\":26}");
        verify(mapper).insert(any(AiMcpToolCallLogEntity.class));
    }

    @Test
    void shouldRecordFailedMcpToolCallAndKeepException() {
        AiMcpToolCallLogMapper mapper = mock(AiMcpToolCallLogMapper.class);
        ToolCallback callback = new McpToolLoggingToolCallback(
                new FixedToolCallback(true),
                logService(mapper),
                "nacos-mcp-agent",
                "/mcp");

        assertThatThrownBy(() -> callback.call("{\"city\":\"\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper).insert(any(AiMcpToolCallLogEntity.class));
    }

    private static McpToolCallLogService logService(
            AiMcpToolCallLogMapper mapper) {
        AgentAuditProperties properties = new AgentAuditProperties();
        properties.setLogEnabled(false);
        return new McpToolCallLogService(properties, mapper);
    }

    private static final class FixedToolCallback implements ToolCallback {

        private final boolean fail;

        private FixedToolCallback(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name("getWeatherByCity")
                    .description("weather")
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            if (fail) {
                throw new IllegalArgumentException("city is blank");
            }
            return "{\"temperature\":26}";
        }
    }
}
