package com.nbcb.agent.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * /chat 接口的请求 DTO
 * <p>
 * 替代原始 {@code Map<String, String>}，提供编译期类型安全和参数校验。
 *
 * @author com.nbcb
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 用户问题（必填；最大 10KB，防 OOM） */
    @NotBlank(message = "问题不能为空")
    @Size(max = 10240, message = "问题内容不能超过 10240 字符")
    private String question;

    /** ★ 是否返回思考过程（可选，默认 false，流式接口自动开启） */
    @Builder.Default
    private boolean showThinking = false;
}
