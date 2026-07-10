package com.nbcb.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.exception.StageValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段间结构化校验器 — 在每个阶段 LLM 产出后立即做代码级 JSON 完整性校验，
 * 防止坏数据流入下游阶段造成 LLM token 浪费。
 * <p>
 * 校验范围仅限"下游阶段运行所必需的最小结构"，不校验业务语义正确性
 * （业务语义由各阶段 prompt 的自检规则保证）。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class StageValidator {

    private static final String PHASE1 = "阶段一[拆解]";
    private static final String PHASE2 = "阶段二[工具映射]";

    private final ObjectMapper objectMapper;

    public StageValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验阶段一拆解结果：必须含 skill_name_candidate、非空 steps 数组，
     * 且每个 step 含 step_number 与 goal。
     *
     * @param decompositionJson 阶段一 LLM 输出 JSON
     * @return 解析后的根节点（供调用方复用，避免重复解析）
     * @throws StageValidationException 结构不合规
     */
    public JsonNode validatePhase1(String decompositionJson) {
        List<String> missing = new ArrayList<>();
        JsonNode root = parseOrThrow(decompositionJson, PHASE1, missing);

        if (root.get("skill_name_candidate") == null
                || root.get("skill_name_candidate").asText().isBlank()) {
            missing.add("skill_name_candidate");
        }

        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            missing.add("steps(非空数组)");
        } else {
            int idx = 0;
            for (JsonNode step : steps) {
                String prefix = "steps[" + idx + "]";
                if (step.get("step_number") == null) {
                    missing.add(prefix + ".step_number");
                }
                if (step.get("goal") == null || step.get("goal").asText().isBlank()) {
                    missing.add(prefix + ".goal");
                }
                idx++;
            }
        }

        if (!missing.isEmpty()) {
            log.warn("★ {} 结构校验失败 — 缺失字段: {}", PHASE1, missing);
            throw new StageValidationException(PHASE1, missing);
        }
        log.info("★ {} 结构校验通过 — {} 个步骤", PHASE1, steps == null ? 0 : steps.size());
        return root;
    }

    /**
     * 校验阶段二工具映射结果：必须含非空 steps 数组，每个 step 含
     * tool_resolution.match_type，且顶层含 tool_summary。
     *
     * @param toolResolutionJson 阶段二 LLM 输出 JSON
     * @return 解析后的根节点
     * @throws StageValidationException 结构不合规
     */
    public JsonNode validatePhase2(String toolResolutionJson) {
        List<String> missing = new ArrayList<>();
        JsonNode root = parseOrThrow(toolResolutionJson, PHASE2, missing);

        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            missing.add("steps(非空数组)");
        } else {
            int idx = 0;
            for (JsonNode step : steps) {
                String prefix = "steps[" + idx + "]";
                JsonNode tr = step.get("tool_resolution");
                if (tr == null) {
                    missing.add(prefix + ".tool_resolution");
                } else if (tr.get("match_type") == null
                        || tr.get("match_type").asText().isBlank()) {
                    missing.add(prefix + ".tool_resolution.match_type");
                }
                idx++;
            }
        }

        if (root.get("tool_summary") == null) {
            missing.add("tool_summary");
        }

        if (!missing.isEmpty()) {
            log.warn("★ {} 结构校验失败 — 缺失字段: {}", PHASE2, missing);
            throw new StageValidationException(PHASE2, missing);
        }
        log.info("★ {} 结构校验通过 — {} 个步骤", PHASE2, steps == null ? 0 : steps.size());
        return root;
    }

    /**
     * 解析 JSON，失败时抛出 StageValidationException
     */
    private JsonNode parseOrThrow(String json, String stageName, List<String> missing) {
        if (json == null || json.isBlank()) {
            missing.add("JSON 内容为空");
            throw new StageValidationException(stageName, missing);
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            missing.add("JSON 不可解析: " + e.getMessage());
            throw new StageValidationException(stageName, missing);
        }
    }
}
