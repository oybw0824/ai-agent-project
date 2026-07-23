package com.nbcb.agent.constant;

/**
 * 提示词常量 — 统一管理所有 Prompt Key
 * <p>
 * Key 命名约定：与 {@code src/main/resources/prompt/} 目录下的 .md 文件名（不含后缀）一致。
 * Nacos 控制台中 Prompt 管理的 key 也应与此处常量对齐。
 *
 * @author com.nbcb
 */
public final class PromptConstant {

    private PromptConstant() {
        // 工具类，禁止实例化
    }

    /** Agent 核心系统提示词 */
    public static final String AGENT_SYSTEM = "agent-system";

    /** Skill 步骤 Markdown 生成提示词 */
    public static final String SKILL_GENERATE_STEPS = "skill-generate-steps";
    /** PRD 拆解与工具映射合并提示词 */
    public static final String SKILL_DECOMPOSE_RESOLVE = "skill-decompose-resolve";
}
