package com.nbcb.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM JSON 响应提取 + 完整性校验工具
 * <p>
 * 提供 LLM 调用的公共 JSON 处理逻辑：从自然语言响应中提取 JSON、
 * 校验 JSON 完整性（可解析性）。
 * <p>
 * 被 Skill 生成服务复用：
 * <ul>
 *   <li>{@code extractJson} — 从 LLM 响应中剥离 markdown 代码块标记，提取纯 JSON</li>
 *   <li>{@code isValidJson} — 校验 JSON 字符串是否可被 Jackson 解析</li>
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
