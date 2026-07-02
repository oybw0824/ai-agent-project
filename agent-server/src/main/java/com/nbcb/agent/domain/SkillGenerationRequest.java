package com.nbcb.agent.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill Generation Agent 请求 DTO
 * <p>
 * 接收用户上传的 PRD 文档、MCP Tool Catalog 和可选 Skill 模板，
 * 由 Agent 自动生成 Skill Markdown 文件。
 *
 * @author com.nbcb
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillGenerationRequest {

    /** PRD 需求文档内容（必填） */
    @NotBlank(message = "PRD 内容不能为空")
    private String prdContent;

    /** MCP Tool Catalog JSON（由 Controller 层自动构建，无需调用方传入） */
    private String mcpCatalog;

    /**
     * Skill 模板（可选）
     * <p>
     * 不传时使用默认模板（从 PromptService 加载）。
     * 模板决定生成结果的结构与格式要求。
     */
    private String template;
}