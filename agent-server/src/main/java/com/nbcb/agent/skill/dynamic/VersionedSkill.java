package com.nbcb.agent.skill.dynamic;

/**
 * 带数据库目标版本的 Skill 定义。
 */
public record VersionedSkill(String version, SkillDefinition definition) {
}
