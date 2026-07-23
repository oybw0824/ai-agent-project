package com.nbcb.agent.skill.dynamic;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.skills.SkillPromptConstants;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * 始终使用本次 Agent 执行快照增强系统提示词。
 */
public class SnapshotSkillsInterceptor extends ModelInterceptor {

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Object value = request.getContext().get(DynamicSkillsAgentHook.SNAPSHOT_STATE_KEY);
        if (!(value instanceof AgentSkillSnapshot snapshot)) {
            return handler.call(request);
        }

        AgentScopedSkillRegistry registry = new AgentScopedSkillRegistry(snapshot);
        if (registry.size() == 0) {
            return handler.call(request);
        }

        String skillsPrompt = SkillPromptConstants.buildSkillsPrompt(
                registry.listAll(), registry, registry.getSystemPromptTemplate());
        SystemMessage systemMessage = request.getSystemMessage();
        SystemMessage enhanced = systemMessage == null
                ? new SystemMessage(skillsPrompt)
                : new SystemMessage(systemMessage.getText() + "\n\n" + skillsPrompt);
        return handler.call(ModelRequest.builder(request).systemMessage(enhanced).build());
    }

    @Override
    public String getName() {
        return "SnapshotSkillsInterceptor";
    }
}
