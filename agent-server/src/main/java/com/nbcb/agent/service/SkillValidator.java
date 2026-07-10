package com.nbcb.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Skill Markdown 格式校验器
 * <p>
 * 自适应校验两种格式：
 * <ul>
 *   <li>四阶段新格式：YAML frontmatter + 适用场景/工具清单/执行流程总览/详细步骤/已知缺口</li>
 *   <li>旧格式：Name/Description/Inputs/Workflow/MCP Tool Calls/Error Handling/Output</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SkillValidator {

    /** 四阶段新格式必需 Section */
    private static final List<SectionRequirement> NEW_FORMAT_SECTIONS = List.of(
            new SectionRequirement("YAML frontmatter (name)", "^---\\s*\\nname:"),
            new SectionRequirement("详细步骤", "#{1,2}\\s*详细步骤"),
            new SectionRequirement("Step 步骤", "#{1,4}\\s*(Step|步骤)\\s*\\d+")
    );

    /** 旧格式必需 Section */
    private static final List<SectionRequirement> OLD_FORMAT_SECTIONS = List.of(
            new SectionRequirement("Name", "#{1,2}\\s*Name"),
            new SectionRequirement("Description", "#{1,2}\\s*Description"),
            new SectionRequirement("Workflow", "#{1,2}\\s*Workflow"),
            new SectionRequirement("MCP Tool Calls", "#{1,2}\\s*MCP\\s*Tool\\s*Calls"),
            new SectionRequirement("Output", "#{1,2}\\s*Output")
    );

    /**
     * 校验结果
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors);
        }
    }

    /**
     * 校验 Skill Markdown 格式，自动检测格式类型
     * <p>
     * 格式检测优先级：
     * 1. YAML frontmatter（--- / name:）→ 四阶段新格式
     * 2. 包含"详细步骤"或"适用场景"或"工具清单" → 四阶段新格式
     * 3. 包含"Workflow"或"Name" → 旧格式
     * 4. 默认 → 四阶段新格式
     */
    public ValidationResult validate(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return ValidationResult.failure(List.of("Markdown 内容为空"));
        }

        boolean isNewFormat = isNewFormat(markdown);
        boolean isOldFormat = isOldFormat(markdown);

        // ★ 修复：新格式优先，避免新格式内容因包含旧格式关键词而被误判
        // 新格式判断：YAML frontmatter（--- name:）或 新格式特征词 >=2 个
        if (isNewFormat) {
            return validateFormat(markdown, NEW_FORMAT_SECTIONS, "四阶段新格式");
        }

        // 明确的旧格式
        if (isOldFormat) {
            return validateFormat(markdown, OLD_FORMAT_SECTIONS, "旧格式");
        }

        // 兜底：两个格式特征都不明显，按旧格式关键词判断
        boolean hasOldFormatHint = markdown.contains("Name") || markdown.contains("Description")
                || markdown.contains("Workflow") || markdown.contains("Output")
                || markdown.contains("MCP Tool Calls");

        if (hasOldFormatHint) {
            return validateFormat(markdown, OLD_FORMAT_SECTIONS, "旧格式");
        }

        // 最终兜底：默认按新格式校验
        return validateFormat(markdown, NEW_FORMAT_SECTIONS, "四阶段新格式");
    }

    private boolean isNewFormat(String markdown) {
        if (markdown.startsWith("---")) {
            Pattern yamlName = Pattern.compile("^---\\s*\\nname:\\s*[^\\n]+", Pattern.MULTILINE);
            if (yamlName.matcher(markdown).find()) {
                return true;
            }
        }

        int newFormatScore = 0;
        if (markdown.contains("详细步骤")) newFormatScore++;
        if (markdown.contains("适用场景")) newFormatScore++;
        if (markdown.contains("工具清单")) newFormatScore++;
        if (markdown.contains("已知缺口")) newFormatScore++;
        if (markdown.contains("执行流程总览")) newFormatScore++;

        return newFormatScore >= 2;
    }

    private boolean isOldFormat(String markdown) {
        int oldFormatScore = 0;
        if (markdown.contains("Name")) oldFormatScore++;
        if (markdown.contains("Description")) oldFormatScore++;
        if (markdown.contains("Workflow")) oldFormatScore++;
        if (markdown.contains("MCP Tool Calls")) oldFormatScore++;
        if (markdown.contains("Output")) oldFormatScore++;

        return oldFormatScore >= 2 && !markdown.startsWith("---");
    }

    private ValidationResult validateFormat(String markdown, List<SectionRequirement> sections, String formatName) {
        List<String> errors = new ArrayList<>();

        for (SectionRequirement section : sections) {
            if (!section.compiledPattern.matcher(markdown).find()) {
                String msg = "缺少必需的 Section: " + section.name;
                errors.add(msg);
                log.warn("★ 校验失败 [{}] — {}", formatName, msg);
            }
        }

        if (errors.isEmpty()) {
            log.info("★ 校验通过 [{}] — 所有 {} 个必需 Section 均存在", formatName, sections.size());
            return ValidationResult.success();
        }

        log.warn("★ 校验未通过 [{}] — {}/{} 个必需 Section 缺失",
                formatName, errors.size(), sections.size());
        return ValidationResult.failure(errors);
    }

    /**
     * Section 校验要求（★ 预编译正则，避免每次校验重复编译）
     */
    private record SectionRequirement(String name, String pattern, Pattern compiledPattern) {
        SectionRequirement(String name, String pattern) {
            this(name, pattern, Pattern.compile(pattern, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE));
        }
    }
}