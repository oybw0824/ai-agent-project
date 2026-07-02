package com.nbcb.agent.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Skill Generation Agent 响应 DTO
 * <p>
 * 包含生成的 Skill Markdown 文本、格式校验结果和处理耗时。
 *
 * @author com.nbcb
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillGenerationResponse {

    /** 生成的 Skill Markdown 完整文本 */
    private String skillMarkdown;

    /** 格式校验是否通过 */
    private boolean valid;

    /** 校验未通过的原因列表（valid=false 时填充） */
    private List<String> validationErrors;

    /** 处理耗时（毫秒） */
    private long processingTimeMs;
}