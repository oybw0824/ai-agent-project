package com.nbcb.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 阶段三：单步骤标准化生成服务（批量 + 并行）
 * <p>
 * 基于阶段一+阶段二产出的 JSON，逐个步骤生成标准化的单步骤 Markdown 执行描述。
 * 采用批量分组 + 多线程并行，25 步 → 5 批次 → 5 线程并发 = 1 轮 LLM 调用。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SingleStepGenerationService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /** ★ 共享线程池（复用，不每次创建，有界防 OOM） */
    private final ExecutorService executor = new ThreadPoolExecutor(
            2,                                      // corePoolSize（Step生成低频操作）
            8,                                      // maxPoolSize
            60L, TimeUnit.SECONDS,                  // keepAlive
            new LinkedBlockingQueue<>(32),          // bounded queue
            r -> {
                Thread t = new Thread(r, "step-gen-");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public SingleStepGenerationService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * ★ 关闭线程池，防止资源泄漏
     */
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

    private static final String BATCH_STEP_PROMPT = """
            你是一个 Skill 步骤标准化编写专家。基于输入的多个步骤 JSON 对象（已包含 tool_resolution），为每个步骤生成标准化的 Markdown 执行描述。

            铁律（A级禁止改写）：
            1. judge_logic 中的计算公式、阈值区间、结论规则——必须逐字来自输入 JSON。
            2. output_text_rules 中的输出文本——必须逐字引用，不得改写。
            3. 若 status 为"缺失"，必须原样输出「【缺失】+ missing_reason」，不得补全。
            4. 若 judge_logic.gap_warning 不为 null，必须标注「【未覆盖区间，需PRD补充】」。
            5. Tool 字段必须严格依据 tool_resolution.match_type 渲染：
               - match_type="完整匹配" → Tool字段写已注册工具名
               - match_type="组合匹配" → 列出全部工具名和对应数据项
               - match_type="未匹配" → 写自动生成工具名，并附加「⚠️ AI生成建议工具，非已注册工具，需人工实现后接入」
            6. 每个步骤必须遵循「调用 MCP 工具获取指标 → 逻辑判断 → 输出文本」的固定顺序。

            每个步骤的输出模板：
            ### Step {step_number}：{goal}
            - **Goal**：{goal}
            - **PRD 引用位置**：{prd_reference}
            - **Tool**：按上述规则5渲染
            - **Tool Input**：根据 tool_resolution 列出实际入参字段
            - **Tool Output**：列出实际出参字段，并注明对应的PRD语义
            - **判断逻辑**：
              1. 计算规则：{judge_logic.formula}（若为 null，标注"本步骤无独立计算公式"）
              2. 阈值对比规则：逐条列出 range → conclusion
              3. 未覆盖区间提示：若 gap_warning 不为 null，标注【未覆盖区间，需PRD补充】；否则省略
            - **输出文本映射**：逐条列出 condition → text（逐字引用）
            - **依赖字段**：列出 depends_on_fields（为空则写"无前序依赖"）
            - **Next Step**：{next_step}
            - **完整性状态**：{status}

            请为以下每个步骤生成 Markdown 描述（按顺序输出，不要遗漏任何步骤）：

            %s""";

    /** 每个批次包含的步骤数，可通过 JVM 参数调整 */
    private static final int BATCH_SIZE = Integer.getInteger("step.generator.batch.size", 5);

    /** 并行线程数，可通过 JVM 参数调整 */
    private static final int PARALLELISM = Integer.getInteger("step.generator.parallelism", 5);

    /** 步骤标题正则，用于解析批量响应 */
    private static final java.util.regex.Pattern STEP_HEADING =
            java.util.regex.Pattern.compile("### Step (\\d+)");

    /**
     * 为所有步骤生成 Markdown 描述（批量 + 并行）
     *
     * @param toolResolutionJson 阶段二工具映射结果 JSON
     * @return 各步骤 Markdown 列表，按 step_number 排序
     */
    public List<String> generateAllSteps(String toolResolutionJson) {
        try {
            JsonNode root = objectMapper.readTree(toolResolutionJson);
            JsonNode stepsNode = root.get("steps");
            int totalSteps = stepsNode.size();
            int batchCount = (int) Math.ceil((double) totalSteps / BATCH_SIZE);
            int parallelism = Math.min(PARALLELISM, batchCount);

            log.info("★ 阶段三 [单步生成] 开始 — {} 个步骤 → {} 批次（每批 {} 步），{} 线程并发",
                    totalSteps, batchCount, BATCH_SIZE, parallelism);

            List<List<JsonNode>> batches = new ArrayList<>();
            for (int i = 0; i < batchCount; i++) {
                int start = i * BATCH_SIZE;
                int end = Math.min((i + 1) * BATCH_SIZE, totalSteps);
                List<JsonNode> batch = new ArrayList<>();
                for (int j = start; j < end; j++) {
                    batch.add(stepsNode.get(j));
                }
                batches.add(batch);
            }

            List<CompletableFuture<List<String>>> futures = batches.stream()
                        .map(batch -> CompletableFuture.supplyAsync(() -> {
                            int first = batch.get(0).get("step_number").asInt();
                            int last = batch.get(batch.size() - 1).get("step_number").asInt();
                            log.info("★ [线程{}] 批次 Step {}-{} 开始",
                                    Thread.currentThread().getName(), first, last);
                            return generateBatch(batch);
                        }, executor))
                        .collect(Collectors.toList());

                List<String> allMarkdowns = new ArrayList<>();
                for (CompletableFuture<List<String>> future : futures) {
                    try {
                        allMarkdowns.addAll(future.get(180, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        log.error("★ 批次执行超时/异常", e);
                    }
                }

                allMarkdowns.sort((a, b) -> {
                    int n1 = extractStepNumber(a);
                    int n2 = extractStepNumber(b);
                    return Integer.compare(n1, n2);
                });

                log.info("★ 阶段三 [单步生成] 完成 — 生成 {} 个步骤 Markdown", allMarkdowns.size());
                return allMarkdowns;
        } catch (JsonProcessingException e) {
            log.error("★ 解析阶段二 JSON 失败", e);
            return List.of("【错误】解析阶段二 JSON 失败: " + e.getMessage());
        }
    }

    /**
     * 生成一个批次的步骤——一次 LLM 调用生成批次中所有步骤
     */
    private List<String> generateBatch(List<JsonNode> batch) {
        StringBuilder stepsJson = new StringBuilder();
        for (JsonNode step : batch) {
            try {
                stepsJson.append(objectMapper.writeValueAsString(step)).append("\n\n");
            } catch (JsonProcessingException e) {
                log.error("★ 序列化步骤 JSON 失败", e);
            }
        }
        String prompt = String.format(BATCH_STEP_PROMPT, stepsJson.toString());
        String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                .getResult().getOutput().getText();
        return parseBatchResponse(response.trim());
    }

    /**
     * 解析批量响应，拆分为独立步骤
     */
    private List<String> parseBatchResponse(String response) {
        List<String> results = new ArrayList<>();
        String[] sections = response.split("(?=### Step \\d+)");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;
            java.util.regex.Matcher m = STEP_HEADING.matcher(trimmed);
            if (m.find()) {
                results.add(trimmed);
            }
        }
        return results;
    }

    /**
     * 从 Markdown 中提取步骤编号，用于排序
     */
    private int extractStepNumber(String markdown) {
        java.util.regex.Matcher m = STEP_HEADING.matcher(markdown);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }
}