package com.nbcb.agent.service.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短路构建器 — 处理纯逻辑步骤的场景
 * <p>
 * 当 PRD 拆解结果中所有步骤均为纯逻辑步骤（无需调用任何工具）时，
 * 直接构建阶段二 JSON，跳过 LLM 调用，提升性能。
 *
 * @author com.nbcb
 */
@Slf4j
public final class ShortCircuitBuilder {

    private ShortCircuitBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 检测是否所有步骤均为纯逻辑步骤（无需任何工具调用）
     * <p>
     * 判定条件：阶段一 JSON 中所有 step 的 tool_input 均为空数组 []。
     *
     * @param decompositionJson 阶段一 JSON
     * @param objectMapper      JSON 解析器
     * @return 合成的阶段二 JSON（短路），null 表示需要正常 LLM 流程
     */
    public static String tryShortCircuit(String decompositionJson, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(decompositionJson);
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty()) {
                log.debug("★ 阶段二短路检测：steps 为空，跳过短路");
                return null;
            }

            // 检测是否所有步骤均为纯逻辑步骤
            boolean allNoTool = checkAllNoTool(steps);
            if (!allNoTool) {
                log.debug("★ 阶段二短路检测：存在需要工具的步骤，跳过短路");
                return null;
            }

            // 构建短路 JSON
            String shortCircuitJson = buildShortCircuitJson(root, steps, objectMapper);
            log.info("★ 阶段二 短路合成 JSON 完成 — {} 个纯逻辑步骤", steps.size());
            return shortCircuitJson;
        } catch (Exception e) {
            log.warn("★ 阶段二短路检测异常，回退正常流程: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查所有步骤是否都不需要工具
     *
     * @param steps 步骤数组
     * @return true 表示所有步骤均为纯逻辑步骤
     */
    private static boolean checkAllNoTool(JsonNode steps) {
        for (JsonNode step : steps) {
            JsonNode toolInput = step.get("tool_input");
            if (toolInput != null && toolInput.isArray() && toolInput.size() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建短路合成 JSON（无需 LLM）
     *
     * @param root        阶段一 JSON 根节点
     * @param steps       步骤数组
     * @param objectMapper JSON 解析器
     * @return 合成的阶段二 JSON 字符串
     * @throws JsonProcessingException JSON 序列化异常
     */
    private static String buildShortCircuitJson(JsonNode root, JsonNode steps, ObjectMapper objectMapper)
            throws JsonProcessingException {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> resultSteps = new ArrayList<>();
        List<Integer> noToolSteps = new ArrayList<>();

        int idx = 0;
        for (JsonNode step : steps) {
            int sn = step.has("step_number") ? step.get("step_number").asInt() : idx + 1;
            noToolSteps.add(sn);

            Map<String, Object> resultStep = new LinkedHashMap<>();
            resultStep.put("step_number", sn);
            resultStep.put("goal", textOr(step.get("goal"), ""));

            // 提取 judge_logic 字段（如果有）
            Object judgeLogic = extractField(step, "judge_logic", objectMapper);
            resultStep.put("judge_logic", judgeLogic);

            // 构建工具解析结果
            Map<String, Object> toolResolution = buildNoToolResolution();
            resultStep.put("tool_resolution", toolResolution);
            resultStep.put("status", textOr(step.get("status"), "完整"));

            resultSteps.add(resultStep);
            idx++;
        }

        // 构建工具摘要
        Map<String, Object> toolSummary = buildToolSummary(noToolSteps);

        result.put("steps", resultSteps);
        result.put("tool_summary", toolSummary);

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    /**
     * 提取字段值（智能处理复杂类型）
     *
     * @param step         步骤节点
     * @param fieldName    字段名
     * @param objectMapper JSON 解析器
     * @return 字段值
     */
    private static Object extractField(JsonNode step, String fieldName, ObjectMapper objectMapper) {
        if (step == null || !step.has(fieldName) || step.get(fieldName).isNull()) {

            return null;
        }
        try {
            // 尝试解析为复杂对象
            return objectMapper.readTree(step.get(fieldName).toString());
        } catch (Exception e) {
            // 回退为文本
            return step.get(fieldName).asText();
        }
    }

    /**
     * 构建无需工具的解析结果
     *
     * @return 工具解析结果 Map
     */
    private static Map<String, Object> buildNoToolResolution() {
        Map<String, Object> toolResolution = new LinkedHashMap<>();
        toolResolution.put("match_type", "无需工具");
        toolResolution.put("reason", "纯逻辑步骤，无工具依赖");
        toolResolution.put("matched_tools", List.of());
        return toolResolution;
    }

    /**
     * 构建工具摘要
     *
     * @param noToolSteps 纯逻辑步骤编号列表
     * @return 工具摘要 Map
     */
    private static Map<String, Object> buildToolSummary(List<Integer> noToolSteps) {
        Map<String, Object> toolSummary = new LinkedHashMap<>();
        toolSummary.put("matched_existing_tools", List.of());
        toolSummary.put("newly_generated_tools", List.of());
        toolSummary.put("steps_with_no_tool_needed", noToolSteps);
        return toolSummary;
    }

    /**
     * 获取 JsonNode 的文本值
     *
     * @param node JsonNode
     * @param def  默认值
     * @return 文本值
     */
    private static String textOr(JsonNode node, String def) {
        if (node == null || node.isNull()) {
            return def;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? def : text;
    }
}