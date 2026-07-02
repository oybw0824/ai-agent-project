package com.nbcb.agent.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * /chat 接口的响应 DTO
 * <p>
 * 包含完整的工具调用链路追踪信息：
 * <ul>
 *   <li>用户问题</li>
 *   <li>Agent 调用的工具列表（名称、入参、出参）</li>
 *   <li>Agent 最终回答</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentChatResponse {

    /** 用户原始问题 */
    private String question;

    /** ★ LLM 自主匹配的技能（calledSkills 的首个元素，向后兼容旧字段名 skillId） */
    private String matchedSkill;

    /** ★ LLM 实际调用的技能列表（通过 read_skill 工具加载的全部技能） */
    private List<String> calledSkills;

    /** Agent 调用过的 MCP 工具列表 */
    private List<ToolCallRecord> toolCalls;

    /** Agent 的最终文本回答 */
    private String answer;

    /** 处理耗时（毫秒） */
    private Long processingTimeMs;

}
