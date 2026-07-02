package com.nbcb.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * LLM JSON 响应提取 + 完整性校验 + 自动重试工具
 * <p>
 * 各阶段 LLM 调用的公共逻辑：从自然语言响应中提取 JSON、
 * 校验 JSON 完整性（可解析性），不完整时自动重试最多 N 次。
 * <p>
 * 被以下服务共用：
 * <ul>
 *   <li>{@link com.nbcb.agent.service.DecomposePrdService} — 阶段一 PRD 拆解</li>
 *   <li>{@link com.nbcb.agent.service.ToolResolutionService} — 阶段二 工具映射</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
public final class JsonRetryHelper {

    private JsonRetryHelper() {
        // 工具类，禁止实例化
    }

    /**
     * 带 JSON 完整性校验 + 自动重试的 LLM 调用
     *
     * @param chatModel  LLM 对话模型
     * @param mapper     JSON 解析器
     * @param prompt     提示词
     * @param stageName  阶段名称（用于日志）
     * @param maxRetries 最大重试次数
     * @return 通过校验的 JSON 字符串
     */
    public static String callWithRetry(ChatModel chatModel, ObjectMapper mapper,
                                        String prompt, String stageName, int maxRetries) {
        String lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String fullPrompt = attempt == 0
                    ? prompt
                    : prompt + "\n\n【重要】上次输出 JSON 不完整，请重新输出完整 JSON，不要截断：\n上次输出片段：\n"
                      + (lastResponse != null
                         ? lastResponse.substring(Math.max(0, lastResponse.length() - 200))
                         : "(无)");

            String response = chatModel.call(new Prompt(new UserMessage(fullPrompt)))
                    .getResult().getOutput().getText();
            lastResponse = response;
            String json = extractJson(response);

            if (isValidJson(mapper, json)) {
                return json;
            }

            if (attempt < maxRetries) {
                log.warn("★ {} JSON 不完整（尝试 {}/{}），正在重试...", stageName, attempt + 1, maxRetries);
            }
        }
        log.error("★ {} JSON 校验失败，已重试 {} 次，返回原始提取结果", stageName, maxRetries);
        // ★ 防御性：lastResponse 为 null 时（极端情况两次 LLM 调用都抛异常）返回空 JSON
        return lastResponse != null ? extractJson(lastResponse) : "{}";
    }

    /**
     * 校验 JSON 字符串是否可解析
     */
    public static boolean isValidJson(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return false;
        if (!json.trim().startsWith("{")) return false;
        if (!json.trim().endsWith("}")) return false;
        try {
            mapper.readTree(json);
            return true;
        } catch (Exception e) {
            log.debug("JSON 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 LLM 响应中提取 JSON（去除可能的 markdown 代码块标记）
     */
    public static String extractJson(String response) {
        if (response == null) return "";
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
