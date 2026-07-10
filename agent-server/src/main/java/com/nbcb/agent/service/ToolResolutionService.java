package com.nbcb.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.exception.LlmJsonInvalidException;
import com.nbcb.agent.service.tool.FeatureExtractor;
import com.nbcb.agent.service.tool.McpCatalogFilter;
import com.nbcb.agent.service.tool.ShortCircuitBuilder;
import com.nbcb.agent.service.tool.ToolScorer;
import com.nbcb.agent.util.JsonRetryHelper;
import com.nbcb.agent.util.PromptFormatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段二：MCP 工具映射服务
 * <p>
 * 为 PRD 拆解出的每个步骤映射 MCP 工具。匹配成功则复用现有工具并给出字段映射；
 * 无法匹配则按命名规范自动生成建议工具方案。含第 4 种 match_type「无需工具」识别
 * （纯逻辑步骤判定）。
 * <p>
 * ★ 优化：对 MCP 工具目录做预过滤，仅将 top-N 个与步骤语义最相关的工具注入 prompt，
 * 减少上下文压力、提升匹配准确率。
 * <p>
 * 走 {@link LlmCallTemplate}（含缓存+超时+重试）；prompt 从 {@link PromptService} 加载
 * （支持 Nacos 热更新 + 本地兜底），对应 classpath:prompt/skill-resolve-tools.md。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class ToolResolutionService {

    private final LlmCallTemplate llmCallTemplate;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;
    private final StageValidator stageValidator;

    private static final String PROMPT_KEY = "skill-resolve-tools";

    /** ★ MCP 工具目录预过滤保留的工具数上限 */
    @Value("${agent.skill-gen.tool-filter.topN:15}")
    private int toolFilterTopN;

    public ToolResolutionService(LlmCallTemplate llmCallTemplate,
                                  PromptService promptService,
                                  ObjectMapper objectMapper,
                                  StageValidator stageValidator) {
        this.llmCallTemplate = llmCallTemplate;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
        this.stageValidator = stageValidator;
    }

    /**
     * 为每个步骤映射 MCP 工具
     *
     * @param decompositionJson 阶段一 JSON
     * @param mcpCatalog        MCP 工具清单 JSON
     * @return 阶段二 JSON（含 tool_resolution 和 tool_summary）
     * @throws LlmJsonInvalidException JSON 解析失败
     */
    public String resolve(String decompositionJson, String mcpCatalog) {
        log.info("★ 阶段二 [工具映射] 开始 — 分解结果长度={} 字符", decompositionJson.length());

        // ★ 优化2：短路检测 — 若所有步骤均为纯逻辑步骤（tool_input 全空），跳过 LLM 调用
        String shortCircuitJson = tryShortCircuit(decompositionJson);
        if (shortCircuitJson != null) {
            log.info("★ 阶段二 [工具映射] 短路 — 所有步骤均为纯逻辑步骤，跳过 LLM 调用");
            return shortCircuitJson;
        }

        String template = promptService.getSystemPrompt(PROMPT_KEY);
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("提示词未加载: " + PROMPT_KEY);
        }

        // ★ MCP 工具目录预过滤：仅保留与步骤语义最相关的 top-N 个工具，降低 prompt 上下文压力
        String filteredCatalog = filterMcpCatalog(decompositionJson, mcpCatalog);

        // 缓存 key：阶段一结果 + 过滤后 MCP 目录的 sha256（目录变化时缓存自动失效）
        String cacheKey = LlmCallTemplate.buildCacheKey("resolve", decompositionJson + filteredCatalog);

        // ★ 缓存预检查：命中则跳过 prompt 构建与 LLM 调用，直接复用上次工具映射结果
        String cachedRaw = llmCallTemplate.peekCache(cacheKey);
        if (cachedRaw != null) {
            String json = JsonRetryHelper.extractJson(cachedRaw);
            if (JsonRetryHelper.isValidJson(objectMapper, json)) {
                stageValidator.validatePhase2(json);
                log.info("★ 阶段二 [工具映射] 缓存命中，跳过 LLM 调用 — JSON 长度={} 字符", json.length());
                return json;
            }
            log.warn("★ 阶段二 [工具映射] 缓存内容不可解析，回退完整 LLM 流程");
        }

        String prompt = PromptFormatUtil.safeFormat(template, decompositionJson, filteredCatalog);

        String response = llmCallTemplate.call(prompt, cacheKey, "阶段二[工具映射]");

        String json = JsonRetryHelper.extractJson(response);
        if (!JsonRetryHelper.isValidJson(objectMapper, json)) {
            throw new LlmJsonInvalidException("阶段二 [工具映射] LLM 返回 JSON 无法解析", json);
        }

        // ★ 阶段间结构化校验：确保阶段三所需的最小字段齐备
        stageValidator.validatePhase2(json);

        log.info("★ 阶段二 [工具映射] 完成 — JSON 长度={} 字符", json.length());
        return json;
    }

    // ==================== ★ 优化2：阶段二短路 ====================

    /**
     * ★ 检测是否所有步骤均为纯逻辑步骤（无需任何工具调用），如是则直接合成阶段二 JSON。
     * <p>
     * 判定条件：阶段一 JSON 中所有 step 的 tool_input 均为空数组 []。
     * <p>
     * 使用 {@link ShortCircuitBuilder} 处理短路逻辑。
     *
     * @param decompositionJson 阶段一 JSON
     * @return 合成 JSON（短路），或 null（需要正常 LLM 流程）
     */
    private String tryShortCircuit(String decompositionJson) {
        return ShortCircuitBuilder.tryShortCircuit(decompositionJson, objectMapper);
    }

    // ==================== ★ MCP 工具目录预过滤 ====================

    /**
     * ★ 基于 PRD 特征词从 MCP 目录中筛选 top-N 个语义最相关的工具。
     * <p>
     * 使用 {@link McpCatalogFilter} 处理过滤逻辑。
     *
     * @param decompositionJson 阶段一 JSON
     * @param mcpCatalog        MCP 工具目录 JSON
     * @return 过滤后的 MCP 目录 JSON
     */
    String filterMcpCatalog(String decompositionJson, String mcpCatalog) {
        return McpCatalogFilter.filter(
                decompositionJson,
                mcpCatalog,
                objectMapper,
                toolFilterTopN
        );
    }
}
