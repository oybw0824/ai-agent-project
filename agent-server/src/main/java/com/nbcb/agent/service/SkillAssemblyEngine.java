package com.nbcb.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.exception.SkillAssemblyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段四：纯 Java 组装引擎（替代原 LLM 组装）
 * <p>
 * 取阶段一/阶段二 JSON + 阶段三 stepMarkdowns，按确定性规则拼装出完整 SKILL.md。
 * 全程零 LLM 调用，结果完全可复现，消除 LLM 幻觉导致的组装错误。
 * <p>
 * 组装规则对齐原 LLM 阶段的输出模板：
 * <ol>
 *   <li>YAML frontmatter（name + description）</li>
 *   <li>校验警告（仅在有缺陷时输出，置于标题之后）</li>
 *   <li>适用场景</li>
 *   <li>工具清单（已复用/建议新增/无需工具，按需输出子章节）</li>
 *   <li>执行流程总览</li>
 *   <li>详细步骤（直接拼接 stepMarkdowns）</li>
 *   <li>已知缺口（业务规则缺口表 + 工具未注册提示）</li>
 * </ol>
 * <p>
 * ★ 优化：拆分为 preAssemble（1-6，不依赖 stepMarkdowns）+ postAssemble（7-8），
 * 使 preAssemble 可在阶段三执行期间并行运行。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SkillAssemblyEngine {

    /** 残留扫描关键词：阶段一占位符若泄漏到阶段三产物，属于渲染缺陷 */
    private static final String RESIDUAL_MARKER = "待阶段二映射";

    private final ObjectMapper objectMapper;

    public SkillAssemblyEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 组装最终 SKILL.md（纯 Java，零 LLM 调用）— 兼容旧调用方
     *
     * @param decompositionJson  阶段一 JSON
     * @param toolResolutionJson 阶段二 JSON（含 tool_resolution 与 tool_summary）
     * @param stepMarkdowns      阶段三步骤 Markdown 列表（已按 step_number 排序）
     * @return 完整 SKILL.md 文本
     */
    public String assemble(String decompositionJson, String toolResolutionJson, List<String> stepMarkdowns) {
        String pre = preAssemble(decompositionJson, toolResolutionJson);
        return postAssemble(pre, stepMarkdowns, toolResolutionJson);
    }

    /**
     * ★ 阶段四前置组装（优化1）：构建不依赖 stepMarkdowns 的 SKILL.md 前缀部分（步骤1-6）。
     * <p>
     * 此方法可在阶段三执行期间并行运行，将阶段四的墙钟耗时降到接近 0。
     *
     * @param decompositionJson  阶段一 JSON
     * @param toolResolutionJson 阶段二 JSON
     * @return 前缀文本（frontmatter + 标题 + 警告 + 场景 + 工具清单 + 流程总览），完整的索引占用符 {STEPS_PLACEHOLDER}
     */
    public String preAssemble(String decompositionJson, String toolResolutionJson) {
        long t0 = System.currentTimeMillis();
        log.info("★ 阶段四 [preAssemble] 开始 — 并行前置组装");

        try {
            JsonNode decompRoot = objectMapper.readTree(decompositionJson);
            JsonNode toolRoot = objectMapper.readTree(toolResolutionJson);

            StringBuilder sb = new StringBuilder();

            // 1. YAML frontmatter
            sb.append(buildFrontmatter(decompRoot)).append("\n\n");

            // 2. 标题
            sb.append("# ").append(resolveTitle(decompRoot)).append("\n\n");

            // 3. 校验警告 — 链断/孤儿步骤（可提前计算），残留扫描留给 postAssemble
            String chainWarnings = buildChainCheckWarnings(decompRoot);
            if (!chainWarnings.isBlank()) {
                sb.append(chainWarnings).append("\n");
            }
            sb.append("{RESIDUAL_WARNINGS}");

            // 4. 适用场景
            sb.append(buildScenarioSection(decompRoot)).append("\n\n");

            // 5. 工具清单
            String toolSection = buildToolListSection(toolRoot);
            if (!toolSection.isBlank()) {
                sb.append(toolSection).append("\n\n");
            }

            // 6. 执行流程总览
            sb.append(buildFlowOverview(toolRoot)).append("\n\n");

            // 步骤占位符
            sb.append("{STEPS}").append("\n\n");

            // 缺口占位符
            sb.append("{GAPS}");

            String pre = sb.toString();
            log.info("★ 阶段四 [preAssemble] 完成 — 前缀长度 {} 字符，耗时 {}ms",
                    pre.length(), System.currentTimeMillis() - t0);
            return pre;
        } catch (Exception e) {
            log.error("★ 阶段四 [preAssemble] 异常", e);
            throw new SkillAssemblyException("前置组装失败: " + e.getMessage(), e);
        }
    }

    /**
     * ★ 阶段四后置组装（优化1）：填充 stepMarkdowns、校验警告、已知缺口。
     * <p>
     * preAssemble 产出带占位符的前缀模板，postAssemble 在 stepMarkdowns 就绪后将占位符替换为实际内容。
     *
     * @param preAssembled   preAssemble() 产出的前缀模板
     * @param stepMarkdowns  阶段三步骤 Markdown 列表
     * @param toolResolutionJson 阶段二 JSON（用于解析缺口+校验）
     * @return 完整 SKILL.md 文本
     */
    public String postAssemble(String preAssembled, List<String> stepMarkdowns, String toolResolutionJson) {
        long t0 = System.currentTimeMillis();
        log.info("★ 阶段四 [postAssemble] 开始 — {} 个步骤", stepMarkdowns.size());

        try {
            JsonNode toolRoot = objectMapper.readTree(toolResolutionJson);
            // ★ 从 stepMarkdowns 扫描残留占位符（不依赖 decompositionJson）
            String warnings = buildValidationWarningsFromSteps(stepMarkdowns);

            String detailSteps = buildDetailSteps(stepMarkdowns);
            String gaps = buildKnownGaps(toolRoot);

            String result = preAssembled
                    .replace("{RESIDUAL_WARNINGS}", warnings.isBlank() ? "" : ("\n\n" + warnings))
                    .replace("{STEPS}", detailSteps)
                    .replace("{GAPS}", gaps.isBlank() ? "" : gaps)
                    .replaceAll("\n{3,}", "\n\n")  // 清理多余空行
                    .trim();

            log.info("★ 阶段四 [postAssemble] 完成 — 长度 {} 字符，耗时 {}ms",
                    result.length(), System.currentTimeMillis() - t0);
            return result;
        } catch (Exception e) {
            log.error("★ 阶段四 [postAssemble] 异常", e);
            throw new SkillAssemblyException("后置组装失败: " + e.getMessage(), e);
        }
    }

    // ==================== 子章节构建 ====================

    /**
     * YAML frontmatter：name + description
     */
    private String buildFrontmatter(JsonNode decompRoot) {
        String name = textOr(decompRoot.get("skill_name_candidate"), "unnamed-skill");
        String description = buildDescription(decompRoot);
        return "---\nname: " + name + "\ndescription: " + description + "\n---";
    }

    /**
     * description = skill_trigger_context + 步骤数概要
     */
    private String buildDescription(JsonNode decompRoot) {
        String trigger = textOr(decompRoot.get("skill_trigger_context"), "");
        JsonNode steps = decompRoot.get("steps");
        int count = (steps != null && steps.isArray()) ? steps.size() : 0;
        StringBuilder desc = new StringBuilder(trigger);
        if (count > 0) {
            if (!trigger.isEmpty() && !trigger.endsWith("。") && !trigger.endsWith(".")) {
                desc.append("。");
            }
            desc.append("本 Skill 共拆解为 ").append(count).append(" 个执行步骤。");
        }
        String result = desc.toString().trim();
        return result.isEmpty() ? "自动生成的 Skill" : result;
    }

    /**
     * 标题：优先用 skill_title（中文），缺失时回退 skill_name_candidate
     */
    private String resolveTitle(JsonNode decompRoot) {
        String title = textOr(decompRoot.get("skill_title"), "");
        if (!title.isBlank()) {
            return title;
        }
        return textOr(decompRoot.get("skill_name_candidate"), "Skill");
    }

    /**
     * 适用场景
     */
    private String buildScenarioSection(JsonNode decompRoot) {
        String trigger = textOr(decompRoot.get("skill_trigger_context"), "未提供触发场景说明");
        return "## 适用场景\n" + trigger;
    }

    /**
     * 工具清单：三类子章节按需输出（空则省略；全空则省略整个章节）
     */
    private String buildToolListSection(JsonNode toolRoot) {
        JsonNode summary = toolRoot.get("tool_summary");
        if (summary == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        // 已复用的已注册工具
        List<String> matched = toStringList(summary.get("matched_existing_tools"));
        if (!matched.isEmpty()) {
            if (!hasContent) {
                sb.append("## 工具清单\n");
                hasContent = true;
            }
            sb.append("\n### 已复用的已注册工具\n");
            Map<String, List<Integer>> toolToSteps = collectMatchedToolSteps(toolRoot);
            for (String toolName : matched) {
                List<Integer> steps = toolToSteps.getOrDefault(toolName, List.of());
                sb.append("- **").append(toolName).append("**");
                if (!steps.isEmpty()) {
                    sb.append(" — 被 ").append(formatStepNumbers(steps)).append(" 调用");
                }
                sb.append("\n");
            }
        }

        // 建议新增工具（AI 生成）
        List<String> generated = toStringList(summary.get("newly_generated_tools"));
        if (!generated.isEmpty()) {
            if (!hasContent) {
                sb.append("## 工具清单\n");
                hasContent = true;
            }
            sb.append("\n### 建议新增工具（⚠️ AI生成方案，未注册，需人工实现）\n");
            Map<String, ToolMeta> genToolMeta = collectGeneratedToolMeta(toolRoot);
            for (String toolName : generated) {
                ToolMeta meta = genToolMeta.get(toolName);
                sb.append("- **").append(toolName).append("**\n");
                if (meta != null) {
                    sb.append("  - 输入：").append(meta.inputDesc).append("\n");
                    sb.append("  - 输出：").append(meta.outputDesc).append("\n");
                    sb.append("  - 覆盖步骤：").append(formatStepNumbers(meta.coveredSteps)).append("\n");
                } else {
                    sb.append("  - 覆盖步骤：详见各步骤 Tool 字段\n");
                }
            }
        }

        // 无需工具调用的步骤
        List<Integer> noToolSteps = toIntList(summary.get("steps_with_no_tool_needed"));
        if (!noToolSteps.isEmpty()) {
            if (!hasContent) {
                sb.append("## 工具清单\n");
                hasContent = true;
            }
            sb.append("\n### 无需工具调用的步骤\n");
            sb.append(formatStepNumbers(noToolSteps)).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * 执行流程总览：Step 1 {goal} → Step 2 {goal} → ... → Step N {goal}（终态）
     */
    private String buildFlowOverview(JsonNode toolRoot) {
        JsonNode steps = toolRoot.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            return "## 执行流程总览\n（无步骤）";
        }
        StringBuilder sb = new StringBuilder("## 执行流程总览\n");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            int sn = intOr(step.get("step_number"), i + 1);
            String goal = truncate(textOr(step.get("goal"), ""), 30);
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append("Step ").append(sn);
            if (!goal.isEmpty()) {
                sb.append(" ").append(goal);
            }
        }
        sb.append("（终态）");
        return sb.toString();
    }

    /**
     * 详细步骤：直接拼接 stepMarkdowns
     */
    private String buildDetailSteps(List<String> stepMarkdowns) {
        return "## 详细步骤\n\n" + String.join("\n\n", stepMarkdowns);
    }

    /**
     * 已知缺口：业务规则缺口表（status:缺失 / gap_warning）+ 工具未注册提示
     */
    private String buildKnownGaps(JsonNode toolRoot) {
        JsonNode steps = toolRoot.get("steps");
        List<String[]> rows = new ArrayList<>();

        if (steps != null && steps.isArray()) {
            int idx = 0;
            for (JsonNode step : steps) {
                int sn = intOr(step.get("step_number"), idx + 1);
                JsonNode judgeLogic = step.get("judge_logic");

                // gap_warning 缺口
                String gapWarning = null;
                if (judgeLogic != null) {
                    JsonNode gw = judgeLogic.get("gap_warning");
                    if (gw != null && !gw.isNull() && !gw.asText().isBlank()) {
                        gapWarning = gw.asText();
                    }
                }
                if (gapWarning != null) {
                    rows.add(new String[]{"Step " + sn, "未覆盖区间（gap_warning）", gapWarning});
                }

                // status:缺失 缺口
                String status = textOr(step.get("status"), "");
                if ("缺失".equals(status)) {
                    String reason = textOr(step.get("missing_reason"), "PRD 未明确，需补充");
                    rows.add(new String[]{"Step " + sn, "规则缺失（status:缺失）", reason});
                }
                idx++;
            }
        }

        if (rows.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("## 已知缺口\n\n");
        sb.append("| 步骤 | 缺口类型 | 描述 |\n");
        sb.append("|------|----------|------|\n");
        for (String[] row : rows) {
            sb.append("| ").append(row[0]).append(" | ").append(row[1])
                    .append(" | ").append(escapePipe(row[2])).append(" |\n");
        }

        // 工具未注册提示（独立于业务规则缺口）
        JsonNode summary = toolRoot.get("tool_summary");
        List<String> generated = (summary != null)
                ? toStringList(summary.get("newly_generated_tools")) : List.of();
        if (!generated.isEmpty()) {
            sb.append("\n> 工具注册问题（`");
            sb.append(String.join("`、`", generated));
            sb.append("` 未注册）已在\"建议新增工具\"区块单独提示，不并入本缺口表。");
        }
        return sb.toString().trim();
    }

    /**
     * ★ 链断/孤儿步骤警告（仅依赖阶段一，可在 preAssemble 中提前计算）
     */
    private String buildChainCheckWarnings(JsonNode decompRoot) {
        List<String> warnings = new ArrayList<>();
        JsonNode chainCheck = decompRoot.get("chain_check");
        if (chainCheck != null) {
            if (boolOr(chainCheck.get("has_broken_link"))) {
                String detail = textOr(chainCheck.get("broken_link_detail"), "存在断链");
                warnings.add("**链路断链**：" + detail);
            }
            if (boolOr(chainCheck.get("has_orphan_step"))) {
                String detail = textOr(chainCheck.get("orphan_step_detail"), "存在孤儿步骤");
                warnings.add("**孤儿步骤**：" + detail);
            }
        }
        if (warnings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("> **⚠️ 校验警告**\n>\n");
        for (int i = 0; i < warnings.size(); i++) {
            sb.append("> ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * ★ 仅基于 stepMarkdowns 的残留警告构建（优化1：postAssemble 专用）
     */
    private String buildValidationWarningsFromSteps(List<String> stepMarkdowns) {
        List<Integer> residualSteps = scanResidualMarkers(stepMarkdowns);
        if (residualSteps.isEmpty()) {
            return "";
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("**⚠️ 残留警告**：以下步骤 Markdown 残留阶段一占位符「"
                + RESIDUAL_MARKER + "」，阶段三渲染可能存在缺陷，涉及 Step "
                + residualSteps);
        return formatWarnings(warnings);
    }

    private List<Integer> scanResidualMarkers(List<String> stepMarkdowns) {
        List<Integer> residualSteps = new ArrayList<>();
        for (int i = 0; i < stepMarkdowns.size(); i++) {
            if (stepMarkdowns.get(i) != null && stepMarkdowns.get(i).contains(RESIDUAL_MARKER)) {
                residualSteps.add(i + 1);
            }
        }
        return residualSteps;
    }

    private String formatWarnings(List<String> warnings) {
        if (warnings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("> **⚠️ 校验警告**\n>\n");
        for (int i = 0; i < warnings.size(); i++) {
            sb.append("> ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
        }
        return sb.toString().trim();
    }

    // ==================== 辅助：工具元数据收集 ====================

    /**
     * 收集每个已注册工具被哪些 step 调用
     */
    private Map<String, List<Integer>> collectMatchedToolSteps(JsonNode toolRoot) {
        Map<String, List<Integer>> map = new LinkedHashMap<>();
        JsonNode steps = toolRoot.get("steps");
        if (steps == null || !steps.isArray()) return map;
        int idx = 0;
        for (JsonNode step : steps) {
            int sn = intOr(step.get("step_number"), idx + 1);
            JsonNode tr = step.get("tool_resolution");
            if (tr == null) {
                idx++;
                continue;
            }
            JsonNode matchedTools = tr.get("matched_tools");
            if (matchedTools != null && matchedTools.isArray()) {
                for (JsonNode mt : matchedTools) {
                    String name = textOr(mt.get("tool_name"), "");
                    if (!name.isEmpty()) {
                        map.computeIfAbsent(name, k -> new ArrayList<>()).add(sn);
                    }
                }
            }
            idx++;
        }
        return map;
    }

    /**
     * 收集每个 AI 生成工具的输入/输出描述与覆盖步骤
     */
    private Map<String, ToolMeta> collectGeneratedToolMeta(JsonNode toolRoot) {
        Map<String, ToolMeta> map = new LinkedHashMap<>();
        JsonNode steps = toolRoot.get("steps");
        if (steps == null || !steps.isArray()) return map;
        int idx = 0;
        for (JsonNode step : steps) {
            int sn = intOr(step.get("step_number"), idx + 1);
            JsonNode tr = step.get("tool_resolution");
            if (tr == null) {
                idx++;
                continue;
            }
            JsonNode autoTool = tr.get("auto_generated_tool");
            if (autoTool == null || autoTool.isNull()) {
                idx++;
                continue;
            }
            String name = textOr(autoTool.get("tool_name"), "");
            if (name.isEmpty()) {
                idx++;
                continue;
            }
            ToolMeta meta = map.computeIfAbsent(name, k -> new ToolMeta());
            meta.coveredSteps.add(sn);
            if (meta.inputDesc == null) {
                meta.inputDesc = formatSchemaFields(autoTool.get("input"), true);
            }
            if (meta.outputDesc == null) {
                meta.outputDesc = formatSchemaFields(autoTool.get("output"), false);
            }
            idx++;
        }
        return map;
    }

    /**
     * 格式化工具入参/出参字段列表
     *
     * @param withType 入参附带类型（出参仅显示名称+含义）
     */
    private String formatSchemaFields(JsonNode fields, boolean withType) {
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode f : fields) {
            String name = textOr(f.get("name"), "?");
            String meaning = textOr(f.get("meaning"), "");
            String type = textOr(f.get("type"), "");
            StringBuilder part = new StringBuilder(name);
            if (!meaning.isEmpty() || !type.isEmpty()) {
                part.append("（");
                if (!meaning.isEmpty()) part.append(meaning);
                if (withType && !type.isEmpty()) {
                    if (!meaning.isEmpty()) part.append("，");
                    part.append(type);
                }
                part.append("）");
            }
            parts.add(part.toString());
        }
        return String.join("、", parts);
    }

    // ==================== 辅助：类型转换 ====================

    private String textOr(JsonNode node, String def) {
        if (node == null || node.isNull()) return def;
        String text = node.asText();
        return text == null || text.isBlank() ? def : text;
    }

    private int intOr(JsonNode node, int def) {
        if (node == null || node.isNull() || !node.canConvertToInt()) return def;
        return node.asInt();
    }

    private boolean boolOr(JsonNode node) {
        if (node == null || node.isNull()) return false;
        return node.asBoolean(false);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) return list;
        for (JsonNode item : node) {
            String text = item.asText();
            if (text != null && !text.isBlank()) {
                list.add(text);
            }
        }
        return list;
    }

    private List<Integer> toIntList(JsonNode node) {
        List<Integer> list = new ArrayList<>();
        if (node == null || !node.isArray()) return list;
        for (JsonNode item : node) {
            if (item.canConvertToInt()) {
                list.add(item.asInt());
            } else {
                // 兼容字符串形式的数字
                try {
                    list.add(Integer.parseInt(item.asText().trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return list;
    }

    private String formatStepNumbers(List<Integer> steps) {
        List<String> parts = new ArrayList<>();
        for (Integer s : steps) {
            parts.add("Step " + s);
        }
        return String.join("、", parts);
    }

    private String truncate(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max) + "…";
    }

    private String escapePipe(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    /** AI 生成工具的元数据（输入/输出描述 + 覆盖步骤） */
    private static class ToolMeta {
        String inputDesc;
        String outputDesc;
        final List<Integer> coveredSteps = new ArrayList<>();
    }
}
