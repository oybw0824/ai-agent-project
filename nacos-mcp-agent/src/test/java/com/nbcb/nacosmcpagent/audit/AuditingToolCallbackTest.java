package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.entity.AiCallAuditLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiCallAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditingToolCallbackTest {

    @Test
    void shouldRecordToolInputAndOutput() {
        AiCallAuditLogMapper mapper = mock(AiCallAuditLogMapper.class);
        ToolCallback callback = new AuditingToolCallback(
                new FixedToolCallback(),
                auditService(mapper),
                "agent-a",
                "node-a");

        String output = callback.call("{\"city\":\"北京\"}");

        assertThat(output).isEqualTo("{\"temperature\":25}");
        verify(mapper).insert(any(AiCallAuditLogEntity.class));
    }

    private static AgentCallAuditService auditService(
            AiCallAuditLogMapper mapper) {
        AgentAuditProperties properties = new AgentAuditProperties();
        properties.setLogEnabled(false);
        return new AgentCallAuditService(properties, mapper);
    }

    private static final class FixedToolCallback implements ToolCallback {

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
            return "{\"temperature\":25}";
        }
    }
}
