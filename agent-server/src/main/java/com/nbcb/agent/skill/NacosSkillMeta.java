package com.nbcb.agent.skill;

import lombok.Data;

import java.util.List;

/**
 * Nacos Skill 元数据 — 从 SKILL.md 解析得到
 * <p>
 * 与框架的 {@code com.alibaba.cloud.ai.graph.skills.SkillMetadata} 区别：
 * 这是 Nacos 原始数据，经 {@link NacosSkillRegistry} 转换为框架格式。
 *
 * @author com.nbcb
 */
@Data
public class NacosSkillMeta {
    /** 技能名称 */
    private String name;
    /** 技能描述 */
    private String description;
    /** 版本号 */
    private String version;
    /** 绑定的 MCP 工具列表 */
    private List<String> tools;
    /** SKILL.md 中 YAML frontmatter 之后的 Markdown 正文（Agent 执行指令） */
    private String instructions;
    /**
     * ★ 原始完整的 SKILL.md 内容（YAML frontmatter + Markdown body）
     * 用于 NacosSkillRegistry → ReadSkillTool 返回完整 skill 内容给 LLM
     */
    private String rawContent;
}
