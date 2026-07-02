package com.nbcb.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.util.JsonRetryHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 阶段二：MCP 工具映射服务
 * <p>
 * 给定阶段一拆出来的每个步骤的数据需求，和 Agent 已注册的 MCP 工具清单，
 * 判断每个步骤能不能用现成工具满足。匹配成功则复用现有工具并输出字段映射对照表；
 * 无法匹配则按命名规范自动生成建议工具方案。
 * <p>
 * 匹配的安全边界比"能不能凑出名字"更重要——宁可判定为未匹配走自动生成，
 * 也不要为了"复用率"硬凹一个不准确的匹配。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class ToolResolutionService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ToolResolutionService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    private static final String RESOLVE_PROMPT = """
            你是一个 MCP 工具映射专家。把 PRD 拆解出的每个步骤的数据需求，与 Agent 已注册的 MCP 工具清单进行匹配。

            铁律：
            1. 匹配必须是语义完整覆盖，不是关键词命中。若已注册工具的能力范围不能完整覆盖步骤所需的语义，判定为未匹配。
            2. 匹配成功必须输出字段映射对照表。逐项写出"PRD语义字段 → 已注册工具实际字段名"的对照。
            3. 允许多工具组合，但必须拆解清楚每个子数据项具体由哪个工具提供。
            4. 未匹配时才允许自动生成，且必须显式标记。自动生成的工具名称、入参、出参全部视为「AI建议方案，未注册，需人工实现后接入」。
            5. 自动生成时优先合并而非新增。若多个步骤的数据需求高度相似（仅参数不同），应合并为一个工具。
            6. 命名规范：自动生成的工具名统一用 snake_case 形式（如 get_revenue_trend）。
            7. 不得修改阶段一产出的任何业务判断规则。

            输出格式（仅输出 JSON，不要 markdown 代码块标记）：
            {
              "steps": [
                {
                  "...原阶段一字段全部原样保留...": "...",
                  "tool_resolution": {
                    "match_type": "完整匹配 或 组合匹配 或 未匹配",
                    "matched_tools": [
                      {
                        "tool_name": "已注册工具名",
                        "field_mapping": [
                          {"prd_field": "PRD语义字段名", "tool_field": "已注册工具实际字段名", "note": "差异说明"}
                        ]
                      }
                    ],
                    "is_auto_generated": false,
                    "auto_generated_tool": {
                      "tool_name": "建议名称，仅未匹配时填写",
                      "input": [{"name": "字段名", "type": "类型", "meaning": "含义"}],
                      "output": [{"name": "字段名", "type": "类型", "meaning": "含义"}],
                      "reused_from_step": null
                    }
                  }
                }
              ],
              "tool_summary": {
                "matched_existing_tools": ["已复用的已注册工具名列表，去重"],
                "newly_generated_tools": ["新建议工具名列表，去重"],
                "steps_with_no_tool_needed": ["纯逻辑计算步骤的step_number"]
              }
            }

            阶段一拆解结果：
            %s

            已注册 MCP 工具清单：
            %s

            请输出 JSON：""";

    /**
     * 为每个步骤映射 MCP 工具（带 JSON 完整性校验 + 自动重试）
     *
     * @param decompositionJson 阶段一的完整 JSON 输出
     * @param mcpCatalog        Agent 已注册的 MCP 工具清单 JSON
     * @return 阶段二 JSON（含 tool_resolution 和 tool_summary）
     */
    public String resolve(String decompositionJson, String mcpCatalog) {
        log.info("★ 阶段二 [工具映射] 开始 — 分解结果长度={} 字符", decompositionJson.length());

        String prompt = String.format(RESOLVE_PROMPT, decompositionJson, mcpCatalog);
        String json = JsonRetryHelper.callWithRetry(chatModel, objectMapper, prompt, "阶段二 [工具映射]", 2);

        log.info("★ 阶段二 [工具映射] 完成 — JSON 长度={} 字符", json.length());
        return json;
    }
}