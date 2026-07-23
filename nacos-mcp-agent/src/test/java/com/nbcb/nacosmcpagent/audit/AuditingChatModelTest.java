package com.nbcb.nacosmcpagent.audit;

import com.nbcb.nacosmcpagent.entity.AiCallAuditLogEntity;
import com.nbcb.nacosmcpagent.mapper.AiCallAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditingChatModelTest {

    @Test
    void shouldRecordModelInputAndOutput() {
        AiCallAuditLogMapper mapper = mock(AiCallAuditLogMapper.class);
        AuditingChatModel model = new AuditingChatModel(
                new FixedChatModel("model-answer"),
                auditService(mapper),
                "agent-a",
                "node-a",
                "model-a");

        ChatResponse response = model.call(new Prompt("hello model"));

        assertThat(response.getResult().getOutput().getText())
                .isEqualTo("model-answer");
        verify(mapper).insert(any(AiCallAuditLogEntity.class));
    }

    private static AgentCallAuditService auditService(
            AiCallAuditLogMapper mapper) {
        AgentAuditProperties properties = new AgentAuditProperties();
        properties.setLogEnabled(false);
        return new AgentCallAuditService(properties, mapper);
    }

    private record FixedChatModel(String answer) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage(answer))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }
    }
}
