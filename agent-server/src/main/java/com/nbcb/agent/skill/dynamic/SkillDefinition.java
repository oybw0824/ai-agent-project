package com.nbcb.agent.skill.dynamic;

/**
 * 完整解析并校验后的 Skill 定义。
 */
public record SkillDefinition(
        String name,
        String description,
        String content,
        String filePath) {
}
