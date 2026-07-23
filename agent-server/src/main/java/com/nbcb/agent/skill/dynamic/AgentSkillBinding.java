package com.nbcb.agent.skill.dynamic;

/**
 * Agent 与 Skill 的当前版本绑定。
 */
public record AgentSkillBinding(
        String agentName,
        String skillName,
        String skillVersion,
        String skillFilePath,
        boolean enabled) {
}
