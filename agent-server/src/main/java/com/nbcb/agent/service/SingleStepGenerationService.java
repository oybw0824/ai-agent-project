package com.nbcb.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段三：单步骤标准化生成服务（批量 + 并行 + 三级降级）
 * <p>
 * <ul>
 *   <li>走 {@link LlmCallTemplate}（含缓存+超时+重试）</li>
 *   <li>三级降级：批次超时 → 单步重试 → 占位兜底</li>
 *   <li>prompt 从 {@link PromptService} 加载，含第4种 match_type 渲染规则</li>
 *   <li>批参数从 application.yml 读取</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SingleStepGenerationService {

    private final LlmCallTemplate llmCallTemplate;
    private final PromptService promptService;
    private final ObjectMapper objectMapper;

    @Value("${agent.skill-gen.step.batch-size:5}")
    private int batchSize;

    /** ★ 优化4：简单步骤大批次大小 */
    @Value("${agent.skill-gen.step.batch-timeout-ms:180000}")
    private long batchTimeoutMs;

    @Value("${agent.skill-gen.step.single-step-timeout-ms:60000}")
    private long singleStepTimeoutMs;

    @Value("${agent.skill-gen.step.thread-pool.core-size:2}")
    private int corePoolSize;

    @Value("${agent.skill-gen.step.thread-pool.max-size:10}")
    private int maxPoolSize;

    @Value("${agent.skill-gen.step.thread-pool.queue-capacity:32}")
    private int queueCapacity;

    @Value("${agent.skill-gen.step.thread-pool.keep-alive-seconds:60}")
    private long keepAliveSeconds;

    private static final String PROMPT_KEY = "skill-generate-steps";

    private static final Pattern STEP_HEADING = Pattern.compile("### Step (\\d+)");

    /** ★ 缓存 prompt 模板，避免重复加载 */
    private final AtomicReference<String> cachedPromptTemplate = new AtomicReference<>();

    /** ★ 优化6：缓存 tool_context 前缀（角色定义、规则、输出模板），与 step JSON 分离 */
    private static final String PROMPT_PLACEHOLDER = "%s";
    private final AtomicReference<String> cachedPromptPrefix = new AtomicReference<>();
    private final AtomicReference<String> cachedPromptSuffix = new AtomicReference<>();

    /** 共享线程池（有界防 OOM） */
    private ExecutorService executor;

    @jakarta.annotation.PostConstruct
    public void initExecutor() {
        this.executor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, keepAliveSeconds, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "step-gen-");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public SingleStepGenerationService(LlmCallTemplate llmCallTemplate,
                                        PromptService promptService,
                                        ObjectMapper objectMapper) {
        this.llmCallTemplate = llmCallTemplate;
        this.promptService = promptService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 为所有步骤生成 Markdown 描述（批量 + 并行 + 三级降级）
     *
     * @param toolResolutionJson 阶段二工具映射结果 JSON
     * @param progressId         进度 ID
     * @return 各步骤 Markdown 列表，按 step_number 排序
     */
    public List<String> generateAllSteps(String toolResolutionJson, String progressId) {
        try {
            JsonNode root = objectMapper.readTree(toolResolutionJson);
            JsonNode stepsNode = root.get("steps");
            if (stepsNode == null || !stepsNode.isArray()) {
                log.error("★ 阶段二输出缺失 steps 字段");
                return List.of("【错误】阶段二输出缺失 steps 字段");
            }
            int totalSteps = stepsNode.size();

            // 统一批次分组
            List<List<JsonNode>> allBatches = new ArrayList<>();
            for (int i = 0; i < totalSteps; i += batchSize) {
                int end = Math.min(i + batchSize, totalSteps);
                List<JsonNode> batch = new ArrayList<>();
                for (int j = i; j < end; j++) batch.add(stepsNode.get(j));
                allBatches.add(batch);
            }

            String prdHash = LlmCallTemplate.sha256(toolResolutionJson).substring(0, 12);
            log.info("★ 阶段三 [单步生成] — {} 步骤 → {} 批次", totalSteps, allBatches.size());

            // 并行执行批次
            List<CompletableFuture<List<String>>> futures = new ArrayList<>();
            for (int i = 0; i < allBatches.size(); i++) {
                final int batchIdx = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    List<JsonNode> batch = allBatches.get(batchIdx);
                    int start = batchIdx * batchSize;
                    log.info("★ [{}] 批次 Step {}-{}", Thread.currentThread().getName(),
                            safeStepNumber(batch.get(0), start),
                            safeStepNumber(batch.get(batch.size() - 1), start + batch.size() - 1));
                    return generateBatch(batch, start, prdHash);
                }, executor));
            }

            List<String> allMarkdowns = collectBatchResults(futures, allBatches);

            allMarkdowns.sort(Comparator.comparingInt(this::extractStepNumber));
            log.info("★ 阶段三 [单步生成] 完成 — 生成 {} 个步骤 Markdown", allMarkdowns.size());
            return allMarkdowns;
        } catch (JsonProcessingException e) {
            log.error("★ 阶段三 解析阶段二 JSON 失败", e);
            return List.of("【错误】解析阶段二 JSON 失败: " + e.getMessage());
        }
    }

    /**
     * 收集各批次并行结果（含超时降级逻辑）
     */
    private List<String> collectBatchResults(List<CompletableFuture<List<String>>> futures,
                                              List<List<JsonNode>> batches) {
        List<String> allMarkdowns = new ArrayList<>();
        int globalStepIndex = 0;
        for (int i = 0; i < futures.size(); i++) {
            List<JsonNode> batch = batches.get(i);
            int firstStep = safeStepNumber(batch.get(0), globalStepIndex);
            int lastStep = safeStepNumber(batch.get(batch.size() - 1),
                    globalStepIndex + batch.size() - 1);
            try {
                allMarkdowns.addAll(futures.get(i).get(batchTimeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException te) {
                futures.get(i).cancel(true);
                log.warn("★ 批次 Step {}-{} 超时（{}ms），启动单步降级重试",
                        firstStep, lastStep, batchTimeoutMs);
                allMarkdowns.addAll(recoverBatchBySingleStep(batch, globalStepIndex, null));
            } catch (Exception e) {
                futures.get(i).cancel(true);
                log.error("★ 批次 Step {}-{} 异常，启动单步降级", firstStep, lastStep, e);
                allMarkdowns.addAll(recoverBatchBySingleStep(batch, globalStepIndex, null));
            }
            globalStepIndex += batch.size();
        }
        return allMarkdowns;
    }

    /**
     * 安全获取 step_number，缺失时用 index+1 兜底
     */
    private int safeStepNumber(JsonNode step, int index) {
        JsonNode sn = step.get("step_number");
        if (sn != null && sn.canConvertToInt()) {
            return sn.asInt();
        }
        int fallback = index + 1;
        log.warn("★ step[{}] 缺失 step_number 字段，使用 index+1={} 作为兜底", index, fallback);
        return fallback;
    }

    /**
     * ★ 优化6：使用缓存的前缀/后缀快速构建 prompt，减少重复字符串拼接
     */
    private String formatPromptWithPrefix(String content) {
        String prefix = cachedPromptPrefix.get();
        String suffix = cachedPromptSuffix.get();
        if (prefix == null) {
            // 首次加载，拆分为前缀+后缀
            String full = loadPromptTemplate();
            loadPromptPrefixSuffix(full);
            prefix = cachedPromptPrefix.get();
            suffix = cachedPromptSuffix.get();
        }
        if (prefix == null) {
            return content; // 回退
        }
        StringBuilder sb = new StringBuilder(prefix.length() + content.length() + (suffix != null ? suffix.length() : 0));
        sb.append(prefix).append("\n").append(content);
        if (suffix != null && !suffix.isBlank()) {
            sb.append("\n").append(suffix);
        }
        return sb.toString();
    }

    /**
     * ★ 优化6：拆分模板为前缀（%s 之前）和后缀（%s 之后）
     */
    private void loadPromptPrefixSuffix(String template) {
        int pctIdx = template.indexOf(PROMPT_PLACEHOLDER);
        if (pctIdx >= 0) {
            String prefix = template.substring(0, pctIdx).trim();
            String suffix = template.substring(pctIdx + PROMPT_PLACEHOLDER.length()).trim();
            cachedPromptPrefix.compareAndSet(null, prefix);
            cachedPromptSuffix.compareAndSet(null, suffix);
            log.debug("★ Prompt 前缀缓存就绪 — prefix={} chars, suffix={} chars",
                    prefix.length(), suffix.length());
        } else {
            // 无 %s 占位符，整体作为前缀
            cachedPromptPrefix.compareAndSet(null, template);
            cachedPromptSuffix.compareAndSet(null, "");
        }
    }

    /**
     * 生成一个批次的步骤（非流式，走 LlmCallTemplate）
     * <p>
     * ★ 优化5：缓存键使用 (prdHash, stepsJson) 组合，跨请求复用。
     * ★ 优化6：使用缓存前缀快速构建 prompt，减少冗余字符串操作。
     */
    private List<String> generateBatch(List<JsonNode> batch, int batchStartIndex, String prdHash) {
        String stepsJson = serializeBatch(batch);

        // ★ 优化5：缓存键 = prdHash:stepsJson 的 SHA-256，相同 PRD 的相同步骤可跨请求命中
        String cacheKey = LlmCallTemplate.buildCacheKey("step-gen", prdHash + ":" + stepsJson);

        // ★ 优化6：使用缓存前缀快速构建 prompt
        String prompt = formatPromptWithPrefix(stepsJson);

        int firstStep = safeStepNumber(batch.get(0), batchStartIndex);
        String response = llmCallTemplate.call(prompt, cacheKey, "阶段三[批" + firstStep + "]");
        List<String> parsed = parseBatchResponse(response.trim());
        if (parsed.size() != batch.size()) {
            llmCallTemplate.evictCache(cacheKey);
            throw new IllegalStateException("批次步骤数量不匹配：期望 " + batch.size()
                    + "，实际 " + parsed.size());
        }
        return parsed;
    }

    /**
     * ★ 降级1：批次失败 → 拆单步并行重试（原串行 for 循环改为 CompletableFuture 并行）。
     * <p>
     * 并行化后最坏耗时从 N × singleStepTimeout 降至 1 × (singleStepTimeout×2) + overhead，
     * 未在超时内完成的步骤用占位 Markdown 兜底，保证步骤不丢失。
     *
     * @param prdHash 阶段二 JSON 哈希（缓存键增强），空则退化为仅 stepsJson 缓存
     */
    private List<String> recoverBatchBySingleStep(List<JsonNode> batch, int batchStartIndex, String prdHash) {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            final int idx = i;
            final JsonNode step = batch.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                int stepNo = safeStepNumber(step, batchStartIndex + idx);
                try {
                    return generateSingleStep(step, batchStartIndex + idx, prdHash);
                } catch (Exception e) {
                    log.error("★ Step {} 单步降级仍失败，生成占位", stepNo, e);
                    return buildPlaceholderMarkdown(step, e.getMessage(), batchStartIndex + idx);
                }
            }, executor));
        }

        List<String> result = new ArrayList<>();
        try {
            // 整体超时 = 单步超时 × 2（并行下留足余量）
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(singleStepTimeoutMs * 2, TimeUnit.MILLISECONDS);
            for (CompletableFuture<String> f : futures) {
                result.add(f.getNow(""));
            }
        } catch (Exception e) {
            log.warn("★ 单步并行降级超时/异常，对未完成步骤生成占位: {}", e.getMessage());
            for (int i = 0; i < futures.size(); i++) {
                String md = futures.get(i).getNow(null);
                if (md != null) {
                    result.add(md);
                } else {
                    JsonNode step = batch.get(i);
                    result.add(buildPlaceholderMarkdown(step, "并行降级超时", batchStartIndex + i));
                }
            }
        }
        return result;
    }

    /**
     * 单步生成（降级用，串行调用）
     * ★ 优化5：使用 prdHash 增强缓存键
     */
    private String generateSingleStep(JsonNode step, int stepIndex, String prdHash) {
        String stepJson;
        try {
            stepJson = objectMapper.writeValueAsString(step);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化步骤失败", e);
        }
        // ★ 优化5：缓存键 = prdHash:stepJson（无 prdHash 时退化为仅 stepJson）
        String cacheInput = (prdHash != null) ? prdHash + ":" + stepJson : stepJson;
        String cacheKey = LlmCallTemplate.buildCacheKey("step-single", cacheInput);

        // ★ 优化6：使用缓存前缀快速构建 prompt
        String prompt = formatPromptWithPrefix(stepJson);
        String response = llmCallTemplate.call(prompt, cacheKey, "阶段三[单步降级]");
        List<String> parsed = parseBatchResponse(response.trim());
        if (parsed.isEmpty()) {
            llmCallTemplate.evictCache(cacheKey);
            return buildPlaceholderMarkdown(step, "解析响应为空", stepIndex);
        }
        return parsed.get(0);
    }

    /**
     * 降级2：占位 Markdown（步骤不丢失，标记需人工补全）
     */
    private String buildPlaceholderMarkdown(JsonNode step, String reason, int stepIndex) {
        int sn = safeStepNumber(step, stepIndex);
        String goal = step.has("goal") && step.get("goal") != null
                ? step.get("goal").asText() : "未知目标";
        return String.format("""
            ### Step %d：%s
            - **Goal**：%s
            - **Tool**：【缺失】本步骤 LLM 生成失败，需人工补全
            - **Tool Input**：【缺失】
            - **Tool Output**：【缺失】
            - **判断逻辑**：【缺失】
            - **输出文本映射**：【缺失】
            - **依赖字段**：【缺失】
            - **Next Step**：【缺失】
            - **完整性状态**：缺失
            - **缺失原因**：自动生成超时/异常：%s""", sn, goal, goal, reason);
    }

    private String serializeBatch(List<JsonNode> batch) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode step : batch) {
            try {
                sb.append(objectMapper.writeValueAsString(step)).append("\n\n");
            } catch (JsonProcessingException e) {
                log.error("★ 序列化步骤 JSON 失败", e);
            }
        }
        return sb.toString();
    }

    private String loadPromptTemplate() {
        String cached = cachedPromptTemplate.get();
        if (cached != null) {
            return cached;
        }
        String template = promptService.getSystemPrompt(PROMPT_KEY);
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("提示词未加载: " + PROMPT_KEY);
        }
        cachedPromptTemplate.compareAndSet(null, template);
        log.debug("★ 加载并缓存 prompt 模板: key={}, length={}", PROMPT_KEY, template.length());
        return cachedPromptTemplate.get();
    }

    /** 刷新 prompt 模板缓存（Nacos 热更新时调用） */
    public void refreshPromptTemplate() {
        cachedPromptTemplate.set(null);
        cachedPromptPrefix.set(null);
        cachedPromptSuffix.set(null);
        log.info("★ Prompt 模板缓存已刷新（含前缀/后缀缓存）");
    }

    private List<String> parseBatchResponse(String response) {
        List<String> results = new ArrayList<>();
        String[] sections = response.split("(?=### Step \\d+)");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = STEP_HEADING.matcher(trimmed);
            if (m.find()) {
                results.add(trimmed);
            }
        }
        return results;
    }

    private int extractStepNumber(String markdown) {
        Matcher m = STEP_HEADING.matcher(markdown);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}
