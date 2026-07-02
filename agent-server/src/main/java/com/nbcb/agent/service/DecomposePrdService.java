package com.nbcb.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.util.JsonRetryHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 阶段一：PRD 步骤拆解服务
 * <p>
 * 把输入的 PRD 文档拆解成结构化的步骤列表 JSON，不生成任何 Markdown 描述、
 * 不做任何文本润色、不涉及工具命名或匹配。tool_name 统一填"待阶段二映射"。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class DecomposePrdService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DecomposePrdService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    private static final String DECOMPOSE_PROMPT = """
            你是一个 PRD 步骤拆解专家。把输入的 PRD 文档拆解成结构化的步骤列表 JSON。

            铁律：
            1. 每个步骤必须能映射到 PRD 中的具体章节/段落，标注引用位置。
            2. 不得合并 PRD 中明确区分的两个判断环节为一步，也不得把一步拆成多步。
            3. 凡 PRD 未明确给出的字段，输出 "status": "缺失"，并在 missing_reason 中说明。
            4. 若某步骤的阈值区间存在数值空隙，在 gap_warning 中明确写出空隙范围。
            5. 步骤之间的依赖关系必须显式标注，不得让流程出现"断链"。
            6. tool_name 字段统一填"待阶段二映射"，本阶段不判断工具。

            输出格式（仅输出 JSON，不要 markdown 代码块标记）：
            {
              "skill_name_candidate": "英文小写+连字符",
              "skill_trigger_context": "触发场景描述",
              "steps": [
                {
                  "step_number": 1,
                  "goal": "本步骤目标",
                  "prd_reference": "PRD中对应章节/段落位置",
                  "tool_name": "待阶段二映射",
                  "tool_input": [{"name": "字段名", "meaning": "含义说明"}],
                  "tool_output": [{"name": "字段名", "meaning": "含义说明"}],
                  "judge_logic": {
                    "formula": "计算公式或null",
                    "threshold_rules": [{"range": "阈值区间", "conclusion": "结论"}],
                    "gap_warning": "空隙说明或null"
                  },
                  "output_text_rules": [{"condition": "条件", "text": "输出文本（逐字引用PRD）"}],
                  "next_step": "下一步描述",
                  "depends_on_fields": ["依赖的前序字段名"],
                  "status": "完整 或 缺失",
                  "missing_reason": "缺失原因或null"
                }
              ],
              "chain_check": {
                "has_broken_link": false,
                "broken_link_detail": null,
                "has_orphan_step": false,
                "orphan_step_detail": null
              }
            }

            PRD 文档：
            %s

            请输出 JSON：""";

    /**
     * 拆解 PRD 为结构化步骤列表（带 JSON 完整性校验 + 自动重试）
     *
     * @param prdContent PRD 文档完整原文
     * @return 阶段一结构化 JSON 字符串
     */
    public String decompose(String prdContent) {
        log.info("★ 阶段一 [拆解] 开始 — PRD 长度={} 字符", prdContent.length());

        String prompt = String.format(DECOMPOSE_PROMPT, prdContent);
        String json = JsonRetryHelper.callWithRetry(chatModel, objectMapper, prompt, "阶段一 [拆解]", 2);

        log.info("★ 阶段一 [拆解] 完成 — JSON 长度={} 字符", json.length());
        return json;
    }
}