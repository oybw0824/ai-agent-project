package com.nbcb.agent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 特征词提取器 — 从 PRD 拆解结果中提取关键特征词
 * <p>
 * 从阶段一 JSON 的 goal、tool_input、tool_output 的 meaning 字段中提取特征词，
 * 用于后续 MCP 工具目录的语义匹配。
 *
 * @author com.nbcb
 */
@Slf4j
public final class FeatureExtractor {

    /** 词项切分：按非中文/非字母数字字符切分 */
    private static final Pattern TERM_SPLIT = Pattern.compile("[^\\u4e00-\\u9fa5a-zA-Z0-9]+");

    /** 最小词长阈值（英文） */
    private static final int MIN_ENGLISH_LENGTH = 3;

    /** 最小词长阈值（中文） */
    private static final int MIN_CHINESE_LENGTH = 2;

    /** 英文正则（仅字母数字） */
    private static final Pattern ASCII_PATTERN = Pattern.compile("^[a-zA-Z0-9]+$");

    private FeatureExtractor() {
        // 工具类，禁止实例化
    }

    /**
     * 从阶段一 JSON 提取特征词（goal + tool_input/tool_output 的 meaning）
     *
     * @param decompositionJson 阶段一 JSON 字符串
     * @param objectMapper       JSON 解析器
     * @return 特征词集合（保持插入顺序）
     */
    public static Set<String> extractFeatureWords(String decompositionJson,
                                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        Set<String> words = new LinkedHashSet<>();
        try {
            JsonNode root = objectMapper.readTree(decompositionJson);
            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray()) {
                log.debug("★ 阶段一 JSON 中未找到 steps 数组");
                return words;
            }

            for (JsonNode step : steps) {
                // 提取 goal 字段
                addTerms(words, textOr(step.get("goal"), ""));
                // 提取 prd_reference 字段
                addTerms(words, textOr(step.get("prd_reference"), ""));

                // 提取 tool_input 的 meaning 字段
                JsonNode inputs = step.get("tool_input");
                if (inputs != null && inputs.isArray()) {
                    for (JsonNode in : inputs) {
                        addTerms(words, textOr(in.get("meaning"), ""));
                        addTerms(words, textOr(in.get("name"), ""));
                    }
                }

                // 提取 tool_output 的 meaning 字段
                JsonNode outputs = step.get("tool_output");
                if (outputs != null && outputs.isArray()) {
                    for (JsonNode out : outputs) {
                        addTerms(words, textOr(out.get("meaning"), ""));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("★ 提取特征词失败: {}", e.getMessage());
        }

        // 过滤过短的词
        words.removeIf(w -> w.length() < MIN_CHINESE_LENGTH);
        log.debug("★ 提取特征词完成，共 {} 个", words.size());
        return words;
    }

    /**
     * 切分文本为词项并加入集合
     *
     * @param words 目标词集合
     * @param text  待切分文本
     */
    private static void addTerms(Set<String> words, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String term : TERM_SPLIT.split(text)) {
            if (term.isBlank()) {
                continue;
            }
            // 中文词保留长度>=2，英文词保留长度>=3
            boolean isAscii = ASCII_PATTERN.matcher(term).matches();
            if ((isAscii && term.length() >= MIN_ENGLISH_LENGTH)
                    || (!isAscii && term.length() >= MIN_CHINESE_LENGTH)) {
                words.add(term.toLowerCase());
            }
        }
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