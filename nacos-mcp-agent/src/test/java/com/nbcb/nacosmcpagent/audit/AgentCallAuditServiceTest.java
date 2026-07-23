package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.entity.AiCallAuditLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiCallAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCallAuditServiceTest {

    @Test
    void shouldPersistCompletePayload() {
        AiCallAuditLogMapper mapper = mock(AiCallAuditLogMapper.class);
        AgentCallAuditService service = new AgentCallAuditService(
                properties(true), mapper);

        service.record(AgentCallAuditEvent.model(
                "agent-a",
                "node-a",
                "model-a",
                "full input",
                "full output",
                true,
                null,
                12));

        ArgumentCaptor<AiCallAuditLogEntity> captor =
                ArgumentCaptor.forClass(AiCallAuditLogEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCallType()).isEqualTo("MODEL");
        assertThat(captor.getValue().getInputText()).isEqualTo("full input");
        assertThat(captor.getValue().getOutputText()).isEqualTo("full output");
        assertThat(captor.getValue().getSuccess()).isEqualTo("1");
    }

    @Test
    void shouldIgnoreDatabaseFailure() {
        AiCallAuditLogMapper mapper = mock(AiCallAuditLogMapper.class);
        when(mapper.insert(any(AiCallAuditLogEntity.class)))
                .thenThrow(new IllegalStateException("db down"));
        AgentCallAuditService service = new AgentCallAuditService(
                properties(true), mapper);

        assertThatCode(() -> service.record(AgentCallAuditEvent.tool(
                "agent-a",
                "node-a",
                "tool-a",
                "input",
                "output",
                true,
                null,
                8))).doesNotThrowAnyException();
    }

    @Test
    void shouldSkipWhenDisabled() {
        AiCallAuditLogMapper mapper = mock(AiCallAuditLogMapper.class);
        AgentCallAuditService service = new AgentCallAuditService(
                properties(false), mapper);

        service.record(AgentCallAuditEvent.tool(
                null,
                null,
                "tool-a",
                "input",
                "output",
                true,
                null,
                8));

        verify(mapper, never()).insert(any(AiCallAuditLogEntity.class));
    }

    private static AgentAuditProperties properties(boolean enabled) {
        AgentAuditProperties properties = new AgentAuditProperties();
        properties.setEnabled(enabled);
        properties.setLogEnabled(false);
        properties.setDbEnabled(true);
        return properties;
    }
}
