package com.nbcb.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nbcb.agent.exception.LlmJsonInvalidException;
import com.nbcb.agent.util.JsonRetryHelper;
import com.nbcb.agent.util.PromptFormatUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阶段一：PRD 步骤拆解服务
 * <p>
 * 把输入的 PRD 文档拆解成结构化的步骤列表 JSON，不生成任何 Markdown 描述、
 * 不做任何文本润色、不涉及工具命名或匹配。tool_name 统一填"待阶段二映射"。
 * <p>
 * 走 {@link LlmCallTemplate}（含缓存+超时+重试）；prompt 从 {@link PromptService} 加载
 * （支持 Nacos 热更新 + 本地兜底），对应 classpath:prompt/skill-decompose.md。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class DecomposePrdService {

    private final LlmCallTemplate llmCallTemplate;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;
    private final StageValidator stageValidator;

    /** PromptService 的 prompt key（对应 classpath:prompt/skill-decompose.md） */
    private static final String PROMPT_KEY = "skill-decompose";

    public DecomposePrdService(LlmCallTemplate llmCallTemplate,
                                PromptService promptService,
                                ObjectMapper objectMapper,
                                StageValidator stageValidator) {
        this.llmCallTemplate = llmCallTemplate;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
        this.stageValidator = stageValidator;
    }

    /**
     * 拆解 PRD 为结构化步骤列表
     *
     * @param prdContent PRD 文档完整原文
     * @return 阶段一结构化 JSON 字符串
     * @throws LlmJsonInvalidException JSON 解析失败（重试耗尽后）
     */
    public String decompose(String prdContent) {
        log.info("★ 阶段一 [拆解] 开始 — PRD 长度={} 字符", prdContent.length());

        // 缓存 key：PRD 内容的 sha256
        String cacheKey = LlmCallTemplate.buildCacheKey("decompose", prdContent);

        // ★ 缓存预检查：命中则跳过 prompt 构建与 LLM 调用，直接复用上次拆解结果（增量重跑场景）
        String cachedRaw = llmCallTemplate.peekCache(cacheKey);
        if (cachedRaw != null) {
            String json = JsonRetryHelper.extractJson(cachedRaw);
            if (JsonRetryHelper.isValidJson(objectMapper, json)) {
                stageValidator.validatePhase1(json);
                log.info("★ 阶段一 [拆解] 缓存命中，跳过 LLM 调用 — JSON 长度={} 字符", json.length());
                return json;
            }
            log.warn("★ 阶段一 [拆解] 缓存内容不可解析，回退完整 LLM 流程");
        }

        // 从 PromptService 加载 prompt（支持 Nacos 热更新 + 本地兜底）
        String template = promptService.getSystemPrompt(PROMPT_KEY);
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("提示词未加载: " + PROMPT_KEY);
        }
        String prompt = PromptFormatUtil.safeFormat(template, prdContent);

        // 走 LlmCallTemplate（含缓存+超时+重试）
        String response = llmCallTemplate.call(prompt, cacheKey, "阶段一[拆解]");

        // JSON 完整性校验
        String json = JsonRetryHelper.extractJson(response);
        if (!JsonRetryHelper.isValidJson(objectMapper, json)) {
            throw new LlmJsonInvalidException("阶段一 [拆解] LLM 返回 JSON 无法解析", json);
        }

        // ★ 阶段间结构化校验：确保下游阶段二所需的最小字段齐备（缺字段时尽早失败，避免浪费下游 LLM token）
        stageValidator.validatePhase1(json);

        log.info("★ 阶段一 [拆解] 完成 — JSON 长度={} 字符", json.length());
        return json;
    }
}