package com.nbcb.mcpclient.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Service;

/**
 * ReactAgent 对话服务。
 */
@Service
public class AgentChatService {

    private final ReactAgent reactAgent;

    public AgentChatService(ReactAgent reactAgent) {
        this.reactAgent = reactAgent;
    }

    public String chat(String question) {
        try {
            AssistantMessage message = reactAgent.call(question);
            return message == null ? "" : message.getText();
        }
        catch (GraphRunnerException ex) {
            throw new IllegalStateException("ReactAgent 执行失败", ex);
        }
    }
}
