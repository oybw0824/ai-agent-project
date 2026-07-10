package com.nbcb.agent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具评分器 — 为 MCP 工具列表打分并排序
 * <p>
 * 基于提取的特征词，计算每个 MCP 工具与 PRD 拆解步骤的语义相关性，
 * 返回按相关性得分降序排序的工具列表（前 N 个）。
 *
 * @author com.nbcb
 */
@Slf4j
public final class ToolScorer {

    /** 匹配得分权重配置 */
    private static final double NAME_MATCH_WEIGHT = 0.6;
    private static final double DESCRIPTION_MATCH_WEIGHT = 0.4;

    private ToolScorer() {
        // 工具类，禁止实例化
    }

    /**
     * 为 MCP 工具列表打分并排序
     *
     * @param mcpToolsJson MCP 工具 JSON 数组
     * @param featureWords 特征词集合
     * @param topN 保留的前 N 个工具
     * @return 排序后的工具列表（name -> score）
     */
    public static Map<String, Double> scoreAndSortTools(JsonNode mcpToolsJson,
                                                        java.util.Set<String> featureWords,
                                                        int topN) {
        Map<String, Double> toolScores = new LinkedHashMap<>();

        if (featureWords.isEmpty() || !mcpToolsJson.isArray()) {
            log.debug("★ 特征词为空或 MCP 工具列表不是数组，跳过评分");
            return toolScores;
        }

        // 计算每个工具的得分
        for (JsonNode tool : mcpToolsJson) {
            String name = tool.get("name") != null ? tool.get("name").asText() : "";
            String description = tool.get("description") != null ? tool.get("description").asText() : "";

            if (name.isEmpty()) {
                continue;
            }

            double score = calculateScore(name, description, featureWords);
            if (score > 0) {
                toolScores.put(name, score);
                log.debug("★ 工具 [{}] 得分: {}", name, score);
            }
        }

        // 按得分降序排序
        toolScores.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(topN)
                .forEach(entry -> {
                    String toolName = entry.getKey();
                    double score = entry.getValue();
                    log.debug("★ 保留工具 [{}] 得分: {}", toolName, score);
                });

        return toolScores;
    }

    /**
     * 为单个工具打分（用于 McpCatalogFilter）
     *
     * @param tool         MCP 工具节点
     * @param featureWords 特征词集合
     * @return 工具得分
     */
    public static double scoreTool(JsonNode tool, java.util.Set<String> featureWords) {
        if (tool == null || featureWords.isEmpty()) {
            return 0.0;
        }

        String name = tool.get("name") != null ? tool.get("name").asText() : "";
        String description = tool.get("description") != null ? tool.get("description").asText() : "";

        return calculateScore(name, description, featureWords);
    }

    /**
     * 计算单个工具的语义相关性得分
     *
     * @param name 工具名
     * @param description 工具描述
     * @param featureWords 特征词
     * @return 相关性得分 (0-1)
     */
    private static double calculateScore(String name, String description, java.util.Set<String> featureWords) {
        double nameScore = calculateNameMatchScore(name, featureWords);
        double descriptionScore = calculateDescriptionMatchScore(description, featureWords);

        double totalScore = nameScore * NAME_MATCH_WEIGHT + descriptionScore * DESCRIPTION_MATCH_WEIGHT;
        log.debug("★ 工具 [{}] - 名称匹配: {}, 描述匹配: {}, 总分: {}",
                name, nameScore, descriptionScore, totalScore);

        return totalScore;
    }

    /**
     * 计算工具名匹配得分
     *
     * @param name 工具名
     * @param featureWords 特征词
     * @return 名称匹配得分 (0-1)
     */
    private static double calculateNameMatchScore(String name, java.util.Set<String> featureWords) {
        if (name.isEmpty()) {
            return 0.0;
        }

        String lowerName = name.toLowerCase();
        int matchCount = 0;

        for (String word : featureWords) {
            if (lowerName.contains(word)) {
                matchCount++;
                log.debug("★ 工具名 [{}] 匹配特征词: {}", name, word);
            }
        }

        // 归一化得分：匹配词数 / 特征词总数（最多1.0）
        return Math.min(1.0, (double) matchCount / featureWords.size());
    }

    /**
     * 计算工具描述匹配得分
     *
     * @param description 工具描述
     * @param featureWords 特征词
     * @return 描述匹配得分 (0-1)
     */
    private static double calculateDescriptionMatchScore(String description, java.util.Set<String> featureWords) {
        if (description == null || description.isEmpty()) {
            return 0.0;
        }

        String lowerDesc = description.toLowerCase();
        int matchCount = 0;

        for (String word : featureWords) {
            if (lowerDesc.contains(word)) {
                matchCount++;
                log.debug("★ 工具描述匹配特征词: {}", word);
            }
        }

        // 归一化得分：匹配词数 / 特征词总数（最多1.0）
        return Math.min(1.0, (double) matchCount / featureWords.size());
    }
}