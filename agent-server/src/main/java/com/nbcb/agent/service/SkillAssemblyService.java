package com.nbcb.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 阶段四：Skill 组装与校验服务
 * <p>
 * 把阶段一的拆解结果 + 阶段二的工具映射结果 + 阶段三生成的全部步骤 Markdown，
 * 组装成一份完整、可被 Agent 直接加载执行的 SKILL.md，并做最终的链路、完整性与工具来源校验。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SkillAssemblyService {

    private final ChatModel chatModel;

    public SkillAssemblyService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    private static final String ASSEMBLE_PROMPT = """
            你是一个 Skill 组装与校验专家。把阶段一的拆解结果 + 阶段二的工具映射结果 + 阶段三生成的全部步骤 Markdown，组装成一份完整的 SKILL.md。

            执行步骤：

            1. 校验（先于任何拼装动作）：
               - chain_check 是否有断链或孤儿步骤
               - 是否存在 status:缺失 的步骤——汇总列出
               - 是否存在 gap_warning——汇总列出
               - tool_summary.newly_generated_tools 是否非空——列出需人工实现的工具清单

            2. 生成 YAML frontmatter：
               - name：使用 skill_name_candidate
               - description：包含"做什么"和"何时触发"

            3. 拼装正文：
               ---
               name: {skill_name_candidate}
               description: {生成的description}
               ---

               # {Skill 标题}

               ## 适用场景
               {skill_trigger_context}

               ## 工具清单

               ### 已复用的已注册工具
               {逐项列出 matched_existing_tools，说明被哪些step调用}

               ### 建议新增工具（⚠️ AI生成方案，未注册，需人工实现）
               {逐项列出 newly_generated_tools，含名称、输入输出、覆盖的step}

               ## 执行流程总览
               {概述 step_number → next_step 的走向}

               ## 详细步骤
               {依次插入全部步骤 Markdown，按 step_number 顺序}

               ## 已知缺口
               {汇总所有 status:缺失 和 gap_warning}

            输出要求：
            - 最终只输出一份完整的 SKILL.md 内容
            - 不对已生成的步骤 Markdown 做二次改写，只做拼装与结构化包装
            - 业务规则缺口与工具未注册问题在文档中始终分开呈现

            阶段一拆解结果：
            %s

            阶段二工具映射结果：
            %s

            阶段三步骤 Markdown（按 step_number 顺序排列）：
            %s

            请生成完整 SKILL.md：""";

    /**
     * 组装最终 SKILL.md
     *
     * @param decompositionJson   阶段一拆解结果 JSON
     * @param toolResolutionJson  阶段二工具映射结果 JSON
     * @param stepMarkdowns       阶段三各步骤 Markdown 列表
     * @return 完整 SKILL.md 文本
     */
    public String assemble(String decompositionJson, String toolResolutionJson, List<String> stepMarkdowns) {
        log.info("★ 阶段四 [组装校验] 开始 — {} 个步骤 Markdown", stepMarkdowns.size());

        String stepsContent = String.join("\n\n", stepMarkdowns);

        String prompt = String.format(ASSEMBLE_PROMPT, decompositionJson, toolResolutionJson, stepsContent);
        String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                .getResult().getOutput().getText();

        String markdown = cleanMarkdown(response);
        log.info("★ 阶段四 [组装校验] 完成 — 长度 {} 字符", markdown.length());
        return markdown;
    }

    private String cleanMarkdown(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```markdown")) {
            trimmed = trimmed.substring(11);
        } else if (trimmed.startsWith("```md")) {
            trimmed = trimmed.substring(5);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}