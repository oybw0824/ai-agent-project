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
 * 纯 Java 组装引擎 — 将阶段二 JSON + 阶段三 Markdown 拼装为完整 SKILL.md。
 * 全程零 LLM 调用，结果完全可复现。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SkillAssemblyEngine {

    private static final String RESIDUAL_MARKER = "待阶段二映射";

    private final ObjectMapper objectMapper;

    public SkillAssemblyEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 组装完整 SKILL.md
     *
     * @param toolResolutionJson 阶段二 JSON（含拆解+工具映射）
     * @param stepMarkdowns      阶段三步骤 Markdown 列表
     * @return 完整 SKILL.md
     */
    public String assemble(String toolResolutionJson, List<String> stepMarkdowns) {
        long t0 = System.currentTimeMillis();
        try {
            JsonNode root = objectMapper.readTree(toolResolutionJson);
            StringBuilder sb = new StringBuilder();

            // 1. YAML frontmatter + 标题
            sb.append(buildFrontmatter(root)).append("\n\n");
            sb.append("# ").append(resolveTitle(root)).append("\n\n");

            // 2. 校验警告
            String warnings = buildWarnings(root, stepMarkdowns);
            if (!warnings.isBlank()) sb.append(warnings).append("\n\n");

            // 3. 适用场景
            sb.append(buildScenarioSection(root)).append("\n\n");

            // 4. 工具清单
            String tools = buildToolListSection(root);
            if (!tools.isBlank()) sb.append(tools).append("\n\n");

            // 5. 执行流程总览
            sb.append(buildFlowOverview(root)).append("\n\n");

            // 6. 详细步骤
            sb.append("## 详细步骤\n\n").append(String.join("\n\n", stepMarkdowns)).append("\n\n");

            // 7. 已知缺口
            String gaps = buildKnownGaps(root);
            if (!gaps.isBlank()) sb.append(gaps);

            String result = sb.toString().replaceAll("\n{3,}", "\n\n").trim();
            log.info("★ 组装完成 — {}字符, {}ms", result.length(), System.currentTimeMillis() - t0);
            return result;
        } catch (Exception e) {
            throw new SkillAssemblyException("组装失败: " + e.getMessage(), e);
        }
    }

    // ==================== 子章节构建 ====================

    private String buildFrontmatter(JsonNode root) {
        String name = textOr(root.get("skill_name_candidate"), "unnamed-skill");
        String trigger = textOr(root.get("skill_trigger_context"), "");
        JsonNode steps = root.get("steps");
        int count = (steps != null && steps.isArray()) ? steps.size() : 0;
        String desc = trigger;
        if (count > 0) {
            if (!desc.isEmpty() && !desc.endsWith("。")) desc += "。";
            desc += "本 Skill 共 " + count + " 个步骤。";
        }
        if (desc.isEmpty()) desc = "自动生成的 Skill";
        return "---\nname: " + name + "\ndescription: " + desc + "\n---";
    }

    private String resolveTitle(JsonNode root) {
        String title = textOr(root.get("skill_title"), "");
        return title.isBlank() ? textOr(root.get("skill_name_candidate"), "Skill") : title;
    }

    private String buildScenarioSection(JsonNode root) {
        return "## 适用场景\n" + textOr(root.get("skill_trigger_context"), "未提供");
    }

    private String buildToolListSection(JsonNode root) {
        JsonNode summary = root.get("tool_summary");
        if (summary == null) return "";

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        List<String> matched = toStringList(summary.get("matched_existing_tools"));
        if (!matched.isEmpty()) {
            sb.append("## 工具清单\n\n### 已复用的已注册工具\n");
            Map<String, List<Integer>> m = collectMatchedToolSteps(root);
            for (String name : matched) {
                List<Integer> st = m.getOrDefault(name, List.of());
                sb.append("- **").append(name).append("**");
                if (!st.isEmpty()) sb.append(" — ").append(formatSteps(st)).append(" 调用");
                sb.append("\n");
            }
            hasContent = true;
        }

        List<String> generated = toStringList(summary.get("newly_generated_tools"));
        if (!generated.isEmpty()) {
            if (!hasContent) sb.append("## 工具清单\n");
            sb.append("\n### 建议新增（⚠️ AI生成，未注册）\n");
            Map<String, ToolMeta> meta = collectGeneratedToolMeta(root);
            for (String name : generated) {
                ToolMeta tm = meta.get(name);
                sb.append("- **").append(name).append("**\n");
                if (tm != null) {
                    sb.append("  - 输入：").append(tm.inputDesc).append("\n");
                    sb.append("  - 输出：").append(tm.outputDesc).append("\n");
                    sb.append("  - 覆盖：").append(formatSteps(tm.coveredSteps)).append("\n");
                }
            }
            hasContent = true;
        }

        List<Integer> noTool = toIntList(summary.get("steps_with_no_tool_needed"));
        if (!noTool.isEmpty()) {
            if (!hasContent) sb.append("## 工具清单\n");
            sb.append("\n### 无需工具调用\n").append(formatSteps(noTool)).append("\n");
        }

        return sb.toString().trim();
    }

    private String buildFlowOverview(JsonNode root) {
        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) return "## 执行流程总览\n（无步骤）";
        StringBuilder sb = new StringBuilder("## 执行流程总览\n");
        for (int i = 0; i < steps.size(); i++) {
            JsonNode s = steps.get(i);
            int sn = intOr(s.get("step_number"), i + 1);
            String goal = truncate(textOr(s.get("goal"), ""), 30);
            if (i > 0) sb.append(" → ");
            sb.append("Step ").append(sn).append(" ").append(goal);
        }
        sb.append("（终态）");
        return sb.toString();
    }

    private String buildWarnings(JsonNode root, List<String> stepMarkdowns) {
        List<String> warnings = new ArrayList<>();
        JsonNode cc = root.get("chain_check");
        if (cc != null) {
            if (boolOr(cc.get("has_broken_link")))
                warnings.add("**链路断链**：" + textOr(cc.get("broken_link_detail"), "存在断链"));
            if (boolOr(cc.get("has_orphan_step")))
                warnings.add("**孤儿步骤**：" + textOr(cc.get("orphan_step_detail"), "存在孤儿步骤"));
        }
        // 残留扫描
        List<Integer> residual = new ArrayList<>();
        for (int i = 0; i < stepMarkdowns.size(); i++) {
            if (stepMarkdowns.get(i) != null && stepMarkdowns.get(i).contains(RESIDUAL_MARKER))
                residual.add(i + 1);
        }
        if (!residual.isEmpty())
            warnings.add("**⚠️ 残留警告**：Step " + residual + " 残留「" + RESIDUAL_MARKER + "」占位符");
        if (warnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("> **⚠️ 校验警告**\n>\n");
        for (int i = 0; i < warnings.size(); i++)
            sb.append("> ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
        return sb.toString().trim();
    }

    private String buildKnownGaps(JsonNode root) {
        JsonNode steps = root.get("steps");
        List<String[]> rows = new ArrayList<>();
        if (steps != null && steps.isArray()) {
            for (int i = 0; i < steps.size(); i++) {
                JsonNode s = steps.get(i);
                int sn = intOr(s.get("step_number"), i + 1);
                JsonNode jl = s.get("judge_logic");
                if (jl != null) {
                    JsonNode gw = jl.get("gap_warning");
                    if (gw != null && !gw.isNull() && !gw.asText().isBlank())
                        rows.add(new String[]{"Step " + sn, "gap_warning", gw.asText()});
                }
                if ("缺失".equals(textOr(s.get("status"), "")))
                    rows.add(new String[]{"Step " + sn, "规则缺失",
                            textOr(s.get("missing_reason"), "PRD 未明确")});
            }
        }
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 已知缺口\n\n| 步骤 | 类型 | 描述 |\n|------|------|------|\n");
        for (String[] r : rows)
            sb.append("| ").append(r[0]).append(" | ").append(r[1])
              .append(" | ").append(escapePipe(r[2])).append(" |\n");

        List<String> gen = toStringList(
                root.has("tool_summary") ? root.get("tool_summary").get("newly_generated_tools") : null);
        if (!gen.isEmpty())
            sb.append("\n> `").append(String.join("`、`", gen)).append("` 未注册，已在工具清单单独提示。");
        return sb.toString().trim();
    }

    // ==================== 辅助方法 ====================

    private Map<String, List<Integer>> collectMatchedToolSteps(JsonNode root) {
        Map<String, List<Integer>> m = new LinkedHashMap<>();
        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray()) return m;
        for (int i = 0; i < steps.size(); i++) {
            JsonNode s = steps.get(i);
            int sn = intOr(s.get("step_number"), i + 1);
            JsonNode tr = s.get("tool_resolution");
            if (tr == null) continue;
            JsonNode mt = tr.get("matched_tools");
            if (mt != null && mt.isArray())
                for (JsonNode t : mt)
                    m.computeIfAbsent(textOr(t.get("tool_name"), ""), k -> new ArrayList<>()).add(sn);
        }
        return m;
    }

    private Map<String, ToolMeta> collectGeneratedToolMeta(JsonNode root) {
        Map<String, ToolMeta> m = new LinkedHashMap<>();
        JsonNode steps = root.get("steps");
        if (steps == null || !steps.isArray()) return m;
        for (int i = 0; i < steps.size(); i++) {
            JsonNode s = steps.get(i);
            int sn = intOr(s.get("step_number"), i + 1);
            JsonNode tr = s.get("tool_resolution");
            if (tr == null) continue;
            JsonNode at = tr.get("auto_generated_tool");
            if (at == null || at.isNull()) continue;
            String name = textOr(at.get("tool_name"), "");
            if (name.isEmpty()) continue;
            ToolMeta tm = m.computeIfAbsent(name, k -> new ToolMeta());
            tm.coveredSteps.add(sn);
            if (tm.inputDesc == null) tm.inputDesc = formatSchema(at.get("input"));
            if (tm.outputDesc == null) tm.outputDesc = formatSchema(at.get("output"));
        }
        return m;
    }

    private String formatSchema(JsonNode fields) {
        if (fields == null || !fields.isArray() || fields.isEmpty()) return "无";
        List<String> parts = new ArrayList<>();
        for (JsonNode f : fields) {
            String name = textOr(f.get("name"), "?");
            String meaning = textOr(f.get("meaning"), "");
            parts.add(meaning.isEmpty() ? name : name + "（" + meaning + "）");
        }
        return String.join("、", parts);
    }

    private String formatSteps(List<Integer> steps) {
        List<String> parts = new ArrayList<>();
        for (Integer s : steps) parts.add("Step " + s);
        return String.join("、", parts);
    }

    private String textOr(JsonNode n, String def) {
        if (n == null || n.isNull()) return def;
        String t = n.asText();
        return t == null || t.isBlank() ? def : t;
    }

    private int intOr(JsonNode n, int def) {
        if (n == null || n.isNull() || !n.canConvertToInt()) return def;
        return n.asInt();
    }

    private boolean boolOr(JsonNode n) {
        return n != null && !n.isNull() && n.asBoolean(false);
    }

    private List<String> toStringList(JsonNode n) {
        List<String> l = new ArrayList<>();
        if (n == null || !n.isArray()) return l;
        for (JsonNode i : n) if (!i.asText().isBlank()) l.add(i.asText());
        return l;
    }

    private List<Integer> toIntList(JsonNode n) {
        List<Integer> l = new ArrayList<>();
        if (n == null || !n.isArray()) return l;
        for (JsonNode i : n) {
            if (i.canConvertToInt()) l.add(i.asInt());
            else try { l.add(Integer.parseInt(i.asText().trim())); } catch (NumberFormatException ignored) {}
        }
        return l;
    }

    private String truncate(String t, int max) { return t.length() <= max ? t : t.substring(0, max) + "…"; }

    private String escapePipe(String t) { return t == null ? "" : t.replace("|", "\\|").replace("\n", " "); }

    private static class ToolMeta {
        String inputDesc, outputDesc;
        final List<Integer> coveredSteps = new ArrayList<>();
    }
}
