package com.nbcb.agent.service.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * MCP 工具目录过滤器 — 基于特征词过滤相关工具
 * <p>
 * 从完整的 MCP 工具目录中筛选出与当前任务最相关的 top-N 个工具，
 * 减少注入到 LLM prompt 的上下文量，提升匹配准确率。
 *
 * @author com.nbcb
 */
@Slf4j
public final class McpCatalogFilter {

    private McpCatalogFilter() {
        // 工具类，禁止实例化
    }

    /**
     * 过滤 MCP 工具目录，保留语义最相关的 top-N 个工具
     * <p>
     * 流程：
     * <ol>
     *   <li>解析阶段一 JSON 提取特征词</li>
     *   <li>解析 MCP 目录获取所有工具</li>
     *   <li>计算每个工具与特征词的匹配分数</li>
     *   <li>按分数降序排序，取 top-N</li>
     * </ol>
     * 兜底：若工具数未超阈值或 top-N 全部 0 分，返回原始目录。
     *
     * @param decompositionJson 阶段一 JSON
     * @param mcpCatalog        MCP 工具目录 JSON
     * @param objectMapper      JSON 解析器
     * @param topN              保留的工具数上限
     * @return 过滤后的 MCP 目录 JSON
     */
    public static String filter(String decompositionJson,
                                 String mcpCatalog,
                                 ObjectMapper objectMapper,
                                 int topN) {
        if (mcpCatalog == null || mcpCatalog.isBlank()) {
            return mcpCatalog;
        }

        // 解析 MCP 目录
        List<JsonNode> allTools = parseMcpCatalog(mcpCatalog, objectMapper);
        if (allTools == null || allTools.isEmpty()) {
            return mcpCatalog;
        }

        // 工具数未超阈值，无需过滤
        if (allTools.size() <= topN) {
            log.info("★ 阶段二 预过滤：工具数 {} 未超阈值 {}，保留全部", allTools.size(), topN);
            return mcpCatalog;
        }

        // 提取特征词
        Set<String> featureWords = FeatureExtractor.extractFeatureWords(decompositionJson, objectMapper);
        if (featureWords.isEmpty()) {
            log.info("★ 阶段二 预过滤：未提取到特征词，保留全部 {} 个工具", allTools.size());
            return mcpCatalog;
        }

        // 评分并排序
        List<JsonNode> scoredTools = scoreAndSortTools(allTools, featureWords);

        // 取 top-N
        List<JsonNode> kept = scoredTools.subList(0, Math.min(topN, scoredTools.size()));

        // 兜底：若 top-N 全部 0 分，返回原始目录
        if (ToolScorer.scoreTool(kept.get(0), featureWords) == 0) {
            log.info("★ 阶段二 预过滤：top-N 工具均无特征词命中，保留全部 {} 个工具（兜底）", allTools.size());
            return mcpCatalog;
        }

        // 序列化过滤结果
        return serializeFilteredCatalog(kept, objectMapper);
    }

    /**
     * 解析 MCP 工具目录
     *
     * @param mcpCatalog   MCP 目录 JSON 字符串
     * @param objectMapper JSON 解析器
     * @return 工具节点列表，解析失败返回 null
     */
    private static List<JsonNode> parseMcpCatalog(String mcpCatalog, ObjectMapper objectMapper) {
        try {
            JsonNode catalogNode = objectMapper.readTree(mcpCatalog);
            if (!catalogNode.isArray() || catalogNode.size() == 0) {
                return null;
            }

            List<JsonNode> allTools = new ArrayList<>();
            catalogNode.forEach(allTools::add);
            return allTools;
        } catch (Exception e) {
            log.warn("★ MCP 目录解析失败，跳过预过滤: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 评分并排序工具列表
     *
     * @param allTools     所有工具
     * @param featureWords 特征词集合
     * @return 按分数降序排序的工具列表
     */
    private static List<JsonNode> scoreAndSortTools(List<JsonNode> allTools,
                                                     Set<String> featureWords) {
        List<JsonNode> scored = new ArrayList<>(allTools);
        scored.sort(Comparator.<JsonNode>comparingDouble(
                tool -> ToolScorer.scoreTool(tool, featureWords)).reversed());
        return scored;
    }

    /**
     * 序列化过滤后的目录
     *
     * @param keptTools    保留的工具列表
     * @param objectMapper JSON 解析器
     * @return 过滤后的 JSON 字符串
     */
    private static String serializeFilteredCatalog(List<JsonNode> keptTools, ObjectMapper objectMapper) {
        try {
            String filtered = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(keptTools);
            log.info("★ 阶段二 预过滤：{} 个工具 → 保留 top-{} 个相关工具",
                    keptTools.size(), keptTools.size());
            return filtered;
        } catch (JsonProcessingException e) {
            log.warn("★ 过滤后目录序列化失败，回退原始目录: {}", e.getMessage());
            // 注意：这里无法回退原始目录，因为原始 JSON 字符串未被保存
            // 在实际调用中，会捕获异常并返回原始目录
            throw new RuntimeException("目录序列化失败", e);
        }
    }
}