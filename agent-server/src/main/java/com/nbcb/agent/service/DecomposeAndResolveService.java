package com.nbcb.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.constant.PromptConstant;
import com.nbcb.agent.exception.LlmJsonInvalidException;
import com.nbcb.agent.exception.StageValidationException;
import com.nbcb.agent.util.JsonRetryHelper;
import com.nbcb.agent.util.PromptFormatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * PRD 拆解 + MCP 工具映射合并服务 — 一次 LLM 调用同时完成两阶段。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class DecomposeAndResolveService {

    private final LlmCallTemplate llmCallTemplate;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;

    private static final String PROMPT_KEY = PromptConstant.SKILL_DECOMPOSE_RESOLVE;

    public DecomposeAndResolveService(LlmCallTemplate llmCallTemplate,
                                       PromptService promptService,
                                       ObjectMapper objectMapper) {
        this.llmCallTemplate = llmCallTemplate;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    /**
     * PRD 拆解 + MCP 工具映射（1 次 LLM 调用，含短路优化）
     *
     * @param prdContent PRD 文档原文
     * @param mcpCatalog MCP 工具清单 JSON
     * @return 含 tool_resolution + tool_summary 的阶段二 JSON
     */
    public String execute(String prdContent, String mcpCatalog) {
        log.info("★ [拆解+映射] 开始 — PRD={}字符", prdContent.length());

        String template = promptService.getSystemPrompt(PROMPT_KEY);
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("提示词未加载: " + PROMPT_KEY);
        }

        String catalog = mcpCatalog != null ? mcpCatalog : "[]";
        String cacheKey = LlmCallTemplate.buildCacheKey("decompose-resolve", prdContent + catalog);

        // 缓存预检查
        String cached = llmCallTemplate.peekCache(cacheKey);
        if (cached != null) {
            try {
                String json = JsonRetryHelper.extractJson(cached);
                if (!JsonRetryHelper.isValidJson(objectMapper, json)) {
                    throw new LlmJsonInvalidException("缓存的拆解+映射 JSON 无法解析", json);
                }
                validateOutput(json);
                log.info("★ [拆解+映射] 缓存命中 — JSON={}字符", json.length());
                return json;
            } catch (RuntimeException e) {
                llmCallTemplate.evictCache(cacheKey);
                log.warn("★ [拆解+映射] 缓存校验失败，已清除并重新调用 LLM: {}", e.getMessage());
            }
        }

        String prompt = PromptFormatUtil.safeFormat(template, prdContent, catalog);
        String response = llmCallTemplate.call(prompt, cacheKey, "拆解+映射");

        String json = JsonRetryHelper.extractJson(response);
        if (!JsonRetryHelper.isValidJson(objectMapper, json)) {
            llmCallTemplate.evictCache(cacheKey);
            throw new LlmJsonInvalidException("拆解+映射 LLM 返回 JSON 无法解析", json);
        }

        try {
            validateOutput(json);
        } catch (RuntimeException e) {
            llmCallTemplate.evictCache(cacheKey);
            throw e;
        }
        log.info("★ [拆解+映射] 完成 — JSON={}字符", json.length());
        return json;
    }

    // ==================== 校验 ====================

    private void validateOutput(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> errors = new ArrayList<>();

            JsonNode steps = root.get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty())
                errors.add("steps 字段缺失或为空");

            if (steps != null && steps.isArray()) {
                for (JsonNode step : steps) {
                    JsonNode tr = step.get("tool_resolution");
                    if (tr == null || tr.get("match_type") == null)
                        errors.add("Step 缺少 tool_resolution.match_type");
                }
            }
            if (root.get("tool_summary") == null)
                errors.add("tool_summary 字段缺失");

            if (!errors.isEmpty())
                throw new StageValidationException("校验失败", errors);
        } catch (JsonProcessingException e) {
            throw new StageValidationException("JSON 解析失败", List.of(e.getMessage()));
        }
    }

}
