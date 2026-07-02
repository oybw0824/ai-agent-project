package com.nbcb.agent.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.nbcb.agent.domain.AgentChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * AgentService 单元测试 — v2.1: LLM 自主匹配技能
 * <p>
 * ★ 与 v2.0 的核心区别：
 * <ul>
 *   <li>chat() 不再接受 skillId 参数 — LLM 根据 system prompt 自主选择</li>
 *   <li>用户消息即原始问题文本，不做任何格式化</li>
 *   <li>响应中 matchedSkill 由 LLM 实际调用的 calledSkills 决定</li>
 * </ul>
 */
@DisplayName("AgentService 单元测试 (v2.1 LLM自主匹配技能)")
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private ReactAgent reactAgent;

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentService = new AgentService(reactAgent);
    }

    @Test
    @DisplayName("正常对话 → 用户消息即原始问题")
    void shouldPassQuestionDirectly() throws GraphRunnerException {
        doReturn(new AssistantMessage("分析完成，风险等级：低"))
                .when(reactAgent).call(anyString());

        AgentChatResponse resp = agentService.chat("检查风险");

        assertThat(resp.getAnswer()).contains("风险等级");
        assertThat(resp.getMatchedSkill()).isNull();  // 无 ThreadLocal → calledSkills 为空
        // ★ 验证 Agent 收到的消息就是原始问题（不做任何格式化）
        verify(reactAgent).call(eq("检查风险"));
    }

    @Test
    @DisplayName("ReactAgent 异常 → 向上传播（由全局异常处理器统一处理）")
    void shouldPropagateException() throws GraphRunnerException {
        doThrow(new RuntimeException("模型超时")).when(reactAgent).call(anyString());

        assertThatThrownBy(() -> agentService.chat("测试"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模型超时");
    }

    @Test
    @DisplayName("无工具调用 → toolCalls 为 null")
    void shouldReturnNullToolCallsWhenEmpty() throws GraphRunnerException {
        doReturn(new AssistantMessage("好的")).when(reactAgent).call(anyString());

        AgentChatResponse resp = agentService.chat("简单问题");
        assertThat(resp.getToolCalls()).isNull();
    }

    @Test
    @DisplayName("用户消息格式：直接传入原始问题")
    void shouldPassRawQuestionDirectly() throws GraphRunnerException {
        doReturn(new AssistantMessage("结果")).when(reactAgent).call(anyString());

        agentService.chat("今天天气如何");

        // ★ 验证消息就是原始问题，不再有技能提示
        verify(reactAgent).call("今天天气如何");
    }
}
