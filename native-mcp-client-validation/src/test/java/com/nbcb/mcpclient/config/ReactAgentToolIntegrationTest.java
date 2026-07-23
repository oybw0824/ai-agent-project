package com.nbcb.mcpclient.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Stub ChatModel 验证 ReactAgent 的工具调用闭环。
 */
class ReactAgentToolIntegrationTest {

    @Test
    void shouldCallToolAndReturnFinalModelAnswer() throws Exception {
        AtomicBoolean toolCalled = new AtomicBoolean();
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("calculate")
                        .description("计算数学表达式")
                        .inputSchema("""
                                {"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}
                                """)
                        .build();
            }

            @Override
            public String call(String toolInput) {
                toolCalled.set(true);
                return "14";
            }
        };

        StubToolCallingChatModel model = new StubToolCallingChatModel();
        ReactAgent agent = ReactAgent.builder()
                .name("test-agent")
                .model(model)
                .systemPrompt("需要计算时调用 calculate 工具")
                .tools(tool)
                .build();

        AssistantMessage answer = agent.call("计算 2+3*4");

        assertThat(toolCalled).isTrue();
        assertThat(model.sawToolResponse()).isTrue();
        assertThat(answer.getText()).isEqualTo("计算结果是 14");
    }

    private static final class StubToolCallingChatModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean sawToolResponse = new AtomicBoolean();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.getAndIncrement() == 0) {
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "calculate",
                                "{\"expression\":\"2+3*4\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }

            sawToolResponse.set(prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance));
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("计算结果是 14"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptions.builder().build();
        }

        private boolean sawToolResponse() {
            return sawToolResponse.get();
        }
    }
}
