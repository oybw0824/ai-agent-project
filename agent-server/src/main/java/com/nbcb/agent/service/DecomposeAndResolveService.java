package com.nbcb.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.constant.PromptConstant;
import com.nbcb.agent.exception.LlmJsonInvalidException;
import com.nbcb.agent.util.JsonRetryHelper;
import com.nbcb.agent.util.PromptFormatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ★ 阶段一/二合并服务（优化3）：一次 LLM 调用同时完成 PRD 拆解 + MCP 工具映射。
 * <p>
 * 替代原来的两阶段串行调用（DecomposePrdService → ToolResolutionService），
 * 消除一次 LLM 往返延迟，一次输出同时包含 decomposition 字段和 tool_resolution 字段。
 * <p>
 * 通过配置开关 {@code agent.skill-gen.merge-phases=true} 启用，
 * 后端用 {@link SkillGenerationGraphConfig#skillGenerationGraphV2}（3 节点 Graph）运行。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class DecomposeAndResolveService {

    private final LlmCallTemplate llmCallTemplate;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;
    private final StageValidator stageValidator;

    private static final String PROMPT_KEY = PromptConstant.SKILL_DECOMPOSE_RESOLVE;

    public DecomposeAndResolveService(LlmCallTemplate llmCallTemplate,
                                       PromptService promptService,
                                       ObjectMapper objectMapper,
                                       StageValidator stageValidator) {
        this.llmCallTemplate = llmCallTemplate;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
        this.stageValidator = stageValidator;
    }

    /**
     * 合并执行：PRD 拆解 + MCP 工具映射（1 次 LLM 调用）
     *
     * @param prdContent   PRD 文档完整原文
     * @param mcpCatalog   MCP 工具清单 JSON
     * @return 阶段二格式的 JSON（含 decomposition 字段 + tool_resolution + tool_summary）
     * @throws LlmJsonInvalidException JSON 解析失败
     */
    public String decomposeAndResolve(String prdContent, String mcpCatalog) {
        log.info("★ 合并阶段 [拆解+映射] 开始 — PRD 长度={} 字符，MCP 目录长度={} 字符",
                prdContent.length(), mcpCatalog != null ? mcpCatalog.length() : 0);

        String template = promptService.getSystemPrompt(PROMPT_KEY);
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("提示词未加载: " + PROMPT_KEY);
        }

        // 缓存 key：PRD + MCP 目录的 SHA-256
        String cacheKey = LlmCallTemplate.buildCacheKey("decompose-resolve",
                prdContent + (mcpCatalog != null ? mcpCatalog : ""));

        // 缓存预检查
        String cachedRaw = llmCallTemplate.peekCache(cacheKey);
        if (cachedRaw != null) {
            String json = JsonRetryHelper.extractJson(cachedRaw);
            if (JsonRetryHelper.isValidJson(objectMapper, json)) {
                stageValidator.validatePhase2(json);
                log.info("★ 合并阶段 [拆解+映射] 缓存命中 — JSON 长度={} 字符", json.length());
                return json;
            }
            log.warn("★ 合并阶段 [拆解+映射] 缓存内容不可解析，回退完整 LLM 流程");
        }

        String prompt = PromptFormatUtil.safeFormat(template, prdContent,
                mcpCatalog != null ? mcpCatalog : "[]");

        String response = llmCallTemplate.call(prompt, cacheKey, "合并阶段[拆解+映射]");

        String json = JsonRetryHelper.extractJson(response);
        if (!JsonRetryHelper.isValidJson(objectMapper, json)) {
            throw new LlmJsonInvalidException("合并阶段 [拆解+映射] LLM 返回 JSON 无法解析", json);
        }

        stageValidator.validatePhase2(json);

        log.info("★ 合并阶段 [拆解+映射] 完成 — JSON 长度={} 字符", json.length());
        return json;
    }
}
