package com.nbcb.agent.skill.dynamic;

/**
 * Skill 文件加载器。
 */
public interface SkillFileLoader {

    SkillDefinition load(String skillName, String version, String filePath);
}
