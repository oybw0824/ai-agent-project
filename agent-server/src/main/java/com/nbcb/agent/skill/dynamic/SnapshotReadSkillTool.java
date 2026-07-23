package com.nbcb.agent.skill.dynamic;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.nbcb.agent.domain.RequestContext;
import com.nbcb.agent.domain.StreamEvent;
import com.nbcb.agent.util.SsePushHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.IOException;
import java.util.function.BiFunction;

/**
 * 从 ToolContext 中的请求快照读取 Skill 正文。
 */
@Slf4j
public class SnapshotReadSkillTool implements
        BiFunction<ReadSkillTool.ReadSkillRequest, ToolContext, String> {

    public static ToolCallback createToolCallback() {
        return FunctionToolCallback.builder(ReadSkillTool.READ_SKILL, new SnapshotReadSkillTool())
                .description(ReadSkillTool.DESCRIPTION)
                .inputType(ReadSkillTool.ReadSkillRequest.class)
                .build();
    }

    @Override
    public String apply(ReadSkillTool.ReadSkillRequest request, ToolContext toolContext) {
        if (request == null || request.skillName == null || request.skillName.isBlank()) {
            return "Error: skill_name is required";
        }

        try {
            AgentSkillSnapshot snapshot = ToolContextHelper.getState(toolContext)
                    .map(state -> state.data().get(DynamicSkillsAgentHook.SNAPSHOT_STATE_KEY))
                    .filter(AgentSkillSnapshot.class::isInstance)
                    .map(AgentSkillSnapshot.class::cast)
                    .orElseThrow(() -> new IllegalStateException("当前请求缺少 Skill 快照"));
            String content = new AgentScopedSkillRegistry(snapshot)
                    .readSkillContent(request.skillName);
            recordSkillLoad(request.skillName, content.length());
            return content;
        } catch (IOException | IllegalStateException ex) {
            log.warn("读取请求 Skill 快照失败: {}", ex.getMessage());
            return "Error: " + ex.getMessage();
        }
    }

    private void recordSkillLoad(String skillName, int contentLength) {
        RequestContext context = RequestContext.current();
        if (context == null) {
            return;
        }
        context.addCalledSkill(skillName);
        if (context.getEmitter() != null) {
            SsePushHelper.push(context.getEmitter(), StreamEvent.skillLoad(skillName, contentLength));
        }
    }
}
