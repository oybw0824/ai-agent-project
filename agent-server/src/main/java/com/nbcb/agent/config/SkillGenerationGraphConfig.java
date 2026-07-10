package com.nbcb.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.nbcb.agent.domain.StageProgressEvent;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.DecomposeAndResolveService;
import com.nbcb.agent.service.DecomposePrdService;
import com.nbcb.agent.service.SkillAssemblyEngine;
import com.nbcb.agent.domain.SkillGenerationProgress;
import com.nbcb.agent.service.SkillValidator;
import com.nbcb.agent.service.SingleStepGenerationService;
import com.nbcb.agent.service.ToolResolutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Skill Generation 四阶段 Graph 配置
 * <p>
 * 流程：阶段一（拆解）→ 阶段二（工具映射）→ 阶段三（单步生成）→ 阶段四（组装校验）
 * <pre>
 * START → decomposePrd → resolveTools → generateSteps → assembleSkill → END
 * 阶段一            阶段二          阶段三           阶段四
 * </pre>
 *
 * @author com.nbcb
 */
@Slf4j
@Configuration
public class SkillGenerationGraphConfig {

    /** State Key 常量 */
    public static final String KEY_PRD_CONTENT = "prdContent";
    public static final String KEY_MCP_CATALOG = "mcpCatalog";
    public static final String TEMPLATE = "template";

    /** 阶段一：PRD 拆解结果 JSON */
    public static final String KEY_DECOMPOSITION_JSON = "decompositionJson";

    /** 阶段二：工具映射结果 JSON */
    public static final String KEY_TOOL_RESOLUTION_JSON = "toolResolutionJson";

    /** 阶段三：各步骤 Markdown 列表 */
    public static final String KEY_STEP_MARKDOWNS = "stepMarkdowns";

    /** ★ 优化1：阶段四前置组装结果（在阶段三执行期间并行构建） */
    public static final String KEY_PRE_ASSEMBLED = "preAssembled";

    /** 阶段四：最终组装完成的 SKILL.md */
    public static final String KEY_SKILL_MARKDOWN = "skillMarkdown";

    /** 校验结果 */
    public static final String KEY_VALID = "valid";
    public static final String KEY_VALIDATION_ERRORS = "validationErrors";

    /** 进度追踪 ID */
    public static final String KEY_PROGRESS_ID = "progressId";

    /**
     * 定义 Key 更新策略
     */
    @Bean
    public KeyStrategyFactory skillGenerationKeyStrategy() {
        return () -> {
            Map<String, KeyStrategy> strategyMap = new HashMap<>();
            strategyMap.put(KEY_PRD_CONTENT, new ReplaceStrategy());
            strategyMap.put(KEY_MCP_CATALOG, new ReplaceStrategy());
            strategyMap.put(TEMPLATE, new ReplaceStrategy());
            strategyMap.put(KEY_DECOMPOSITION_JSON, new ReplaceStrategy());
            strategyMap.put(KEY_TOOL_RESOLUTION_JSON, new ReplaceStrategy());
            strategyMap.put(KEY_STEP_MARKDOWNS, new ReplaceStrategy());
            strategyMap.put(KEY_PRE_ASSEMBLED, new ReplaceStrategy());
            strategyMap.put(KEY_SKILL_MARKDOWN, new ReplaceStrategy());
            strategyMap.put(KEY_VALID, new ReplaceStrategy());
            strategyMap.put(KEY_VALIDATION_ERRORS, new ReplaceStrategy());
            strategyMap.put(KEY_PROGRESS_ID, new ReplaceStrategy());
            return strategyMap;
        };
    }

    /**
     * 节点 1：阶段一 — PRD 步骤拆解（走 LlmCallTemplate + PromptService）
     */
    @Bean
    public NodeAction decomposePrdNode(DecomposePrdService service, AgentMetrics metrics,
                                        ApplicationEventPublisher eventPublisher) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String prd = (String) state.value(KEY_PRD_CONTENT).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(eventPublisher, progressId, "decomposePrd", StageProgressEvent.Status.START, null);
            String decompositionJson = service.decompose(prd);
            reportStage(eventPublisher, progressId, "decomposePrd", StageProgressEvent.Status.COMPLETE,
                    "拆解完成，JSON长度=" + decompositionJson.length() + "字符");
            metrics.skillGenPhaseDecompose.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);
            log.info("★ Graph Node [decomposePrd] 完成 — 阶段一拆解完毕");
            return Map.of(KEY_DECOMPOSITION_JSON, decompositionJson);
        };
    }

    /**
     * 节点 2：阶段二 — MCP 工具映射（含第4种 match_type "无需工具"）
     */
    @Bean
    public NodeAction resolveToolsNode(ToolResolutionService service, AgentMetrics metrics,
                                        ApplicationEventPublisher eventPublisher) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String decompositionJson = (String) state.value(KEY_DECOMPOSITION_JSON).orElseThrow();
            String mcpCatalog = (String) state.value(KEY_MCP_CATALOG).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(eventPublisher, progressId, "resolveTools", StageProgressEvent.Status.START, null);
            String toolResolutionJson = service.resolve(decompositionJson, mcpCatalog);
            reportStage(eventPublisher, progressId, "resolveTools", StageProgressEvent.Status.COMPLETE,
                    "工具映射完成，JSON长度=" + toolResolutionJson.length() + "字符");
            metrics.skillGenPhaseToolMap.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);
            log.info("★ Graph Node [resolveTools] 完成 — 阶段二工具映射完毕");
            return Map.of(KEY_TOOL_RESOLUTION_JSON, toolResolutionJson);
        };
    }

    /**
     * 节点 3：阶段三 — 单步生成（走 LlmCallTemplate + 三级降级）
     * <p>
     * ★ 优化1：在阶段三开始时异步启动阶段四前置组装（preAssemble），与步骤生成并行执行。
     */
    @Bean
    public NodeAction generateStepsNode(SingleStepGenerationService service, AgentMetrics metrics,
                                         ApplicationEventPublisher eventPublisher,
                                         SkillAssemblyEngine assemblyEngine) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String toolResolutionJson = (String) state.value(KEY_TOOL_RESOLUTION_JSON).orElseThrow();
            String decompositionJson = (String) state.value(KEY_DECOMPOSITION_JSON).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(eventPublisher, progressId, "generateSteps", StageProgressEvent.Status.START, null);

            // ★ 优化1：异步启动阶段四前置组装（不依赖 stepMarkdowns，与阶段三并行）
            CompletableFuture<String> preAssembleFuture = CompletableFuture.supplyAsync(
                    () -> assemblyEngine.preAssemble(decompositionJson, toolResolutionJson));
            log.info("★ Graph Node [generateSteps] 已启动异步 preAssemble");

            List<String> stepMarkdowns = service.generateAllSteps(toolResolutionJson, progressId);
            reportStage(eventPublisher, progressId, "generateSteps", StageProgressEvent.Status.COMPLETE,
                    "生成" + stepMarkdowns.size() + "个步骤Markdown");
            metrics.skillGenPhaseStepGen.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);

            // ★ 等待 preAssemble（通常已在步骤生成期间完成，耗时接近 0）
            String preAssembled;
            try {
                preAssembled = preAssembleFuture.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("★ preAssemble 未在等待窗口内完成，降级为同步组装", e);
                preAssembled = null;
            }

            log.info("★ Graph Node [generateSteps] 完成 — 生成 {} 个步骤 Markdown", stepMarkdowns.size());
            if (preAssembled != null) {
                return Map.of(KEY_STEP_MARKDOWNS, stepMarkdowns, KEY_PRE_ASSEMBLED, preAssembled);
            }
            return Map.of(KEY_STEP_MARKDOWNS, stepMarkdowns);
        };
    }

    /**
     * 节点 4：阶段四 — Skill 组装与校验
     * <p>
     * ★ 优化1：优先使用阶段三期间并行构建的 preAssemble 结果，仅做 postAssemble 填充步骤；
     * 若 preAssemble 未就绪则回退到完整 assemble()。
     */
    @Bean
    public NodeAction assembleSkillNode(SkillAssemblyEngine assemblyEngine, SkillValidator validator,
                                         AgentMetrics metrics, ApplicationEventPublisher eventPublisher) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String decompositionJson = (String) state.value(KEY_DECOMPOSITION_JSON).orElseThrow();
            String toolResolutionJson = (String) state.value(KEY_TOOL_RESOLUTION_JSON).orElseThrow();
            @SuppressWarnings("unchecked")
            List<String> stepMarkdowns = (List<String>) state.value(KEY_STEP_MARKDOWNS).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(eventPublisher, progressId, "assembleSkill", StageProgressEvent.Status.START, null);

            // ★ 优化1：若 preAssemble 已就绪，直接 postAssemble（毫秒级）；否则完整组装
            String preAssembled = (String) state.value(KEY_PRE_ASSEMBLED).orElse(null);
            String skillMarkdown;
            if (preAssembled != null) {
                skillMarkdown = assemblyEngine.postAssemble(preAssembled, stepMarkdowns, toolResolutionJson);
                log.info("★ Graph Node [assembleSkill] postAssemble 完成（预组装命中）— 长度 {} 字符", skillMarkdown.length());
            } else {
                skillMarkdown = assemblyEngine.assemble(decompositionJson, toolResolutionJson, stepMarkdowns);
                log.info("★ Graph Node [assembleSkill] 完整组装完成（预组装未命中）— 长度 {} 字符", skillMarkdown.length());
            }

            SkillValidator.ValidationResult validationResult = validator.validate(skillMarkdown);
            log.info("★ Graph Node [assembleSkill] 校验={}",
                    validationResult.valid() ? "通过" : "失败");

            reportStage(eventPublisher, progressId, "assembleSkill", StageProgressEvent.Status.COMPLETE,
                    "SKILL.md长度=" + skillMarkdown.length() + "字符，校验=" + (validationResult.valid() ? "通过" : "失败"));

            metrics.skillGenPhaseAssembly.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);

            return Map.of(
                    KEY_SKILL_MARKDOWN, skillMarkdown,
                    KEY_VALID, validationResult.valid(),
                    KEY_VALIDATION_ERRORS, validationResult.errors()
            );
        };
    }

    /** ★ 通过 Spring Event 上报阶段进度（解耦 Graph 节点与进度管理） */
    private static void reportStage(ApplicationEventPublisher eventPublisher,
                                     String progressId, String stageName,
                                     StageProgressEvent.Status status, String detail) {
        if (progressId == null || eventPublisher == null) return;
        eventPublisher.publishEvent(new StageProgressEvent(progressId, stageName, status, detail));
        // ★ 同时兼容旧的 SkillGenerationProgress（渐进式迁移）
        SkillGenerationProgress progress = SkillGenerationProgress.get(progressId);
        if (progress != null) {
            if (status == StageProgressEvent.Status.START) {
                progress.stageStart(stageName);
            } else {
                progress.stageComplete(stageName, detail);
            }
        }
    }

    // ==================== ★ 优化3：阶段一/二合并节点 ====================

    /**
     * ★ 优化3：合并阶段一+阶段二节点 — 一次 LLM 调用同时完成 PRD 拆解和工具映射
     */
    @Bean
    public NodeAction decomposeAndResolveNode(DecomposeAndResolveService service, AgentMetrics metrics,
                                               ApplicationEventPublisher eventPublisher) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String prd = (String) state.value(KEY_PRD_CONTENT).orElseThrow();
            String mcpCatalog = (String) state.value(KEY_MCP_CATALOG).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(eventPublisher, progressId, "decomposeAndResolve", StageProgressEvent.Status.START, null);

            String result = service.decomposeAndResolve(prd, mcpCatalog);

            reportStage(eventPublisher, progressId, "decomposeAndResolve", StageProgressEvent.Status.COMPLETE,
                    "合并完成，JSON长度=" + result.length() + "字符");
            metrics.skillGenPhaseDecompose.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);
            log.info("★ Graph Node [decomposeAndResolve] 完成 — 合并阶段一+二");

            return Map.of(KEY_TOOL_RESOLUTION_JSON, result);
        };
    }

    // ==================== Graph 定义 ====================

    /**
     * 四阶段 Skill Generation Graph（默认，未启用 Phase 合并时使用）
     */
    @Bean
    @ConditionalOnProperty(name = "agent.skill-gen.merge-phases", havingValue = "false", matchIfMissing = true)
    public CompiledGraph skillGenerationGraph(
            KeyStrategyFactory keyStrategyFactory,
            NodeAction decomposePrdNode,
            NodeAction resolveToolsNode,
            NodeAction generateStepsNode,
            NodeAction assembleSkillNode) {

        try {
            StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                    .addNode("decomposePrd", AsyncNodeAction.node_async(decomposePrdNode))
                    .addNode("resolveTools", AsyncNodeAction.node_async(resolveToolsNode))
                    .addNode("generateSteps", AsyncNodeAction.node_async(generateStepsNode))
                    .addNode("assembleSkill", AsyncNodeAction.node_async(assembleSkillNode))
                    .addEdge(StateGraph.START, "decomposePrd")
                    .addEdge("decomposePrd", "resolveTools")
                    .addEdge("resolveTools", "generateSteps")
                    .addEdge("generateSteps", "assembleSkill")
                    .addEdge("assembleSkill", StateGraph.END);

            CompiledGraph compiledGraph = stateGraph.compile();
            log.info("★ 四阶段 Skill Generation Graph 编译完成");
            return compiledGraph;
        } catch (GraphStateException e) {
            log.error("Graph 编译失败", e);
            throw new RuntimeException("Skill Generation Graph 初始化失败", e);
        }
    }

    /**
     * ★ 优化3：三阶段合并 Skill Generation Graph（agent.skill-gen.merge-phases=true 时启用）
     * <pre>
     * START → decomposeAndResolve → generateSteps → assembleSkill → END
     *      （阶段一+二合并）        阶段三          阶段四
     * </pre>
     */
    @Bean
    @ConditionalOnProperty(name = "agent.skill-gen.merge-phases", havingValue = "true")
    public CompiledGraph skillGenerationGraphV2(
            KeyStrategyFactory keyStrategyFactory,
            @org.springframework.beans.factory.annotation.Qualifier("decomposeAndResolveNode") NodeAction decomposeAndResolveNode,
            NodeAction generateStepsNode,
            NodeAction assembleSkillNode) {

        try {
            StateGraph stateGraph = new StateGraph(keyStrategyFactory)
                    .addNode("decomposeAndResolve", AsyncNodeAction.node_async(decomposeAndResolveNode))
                    .addNode("generateSteps", AsyncNodeAction.node_async(generateStepsNode))
                    .addNode("assembleSkill", AsyncNodeAction.node_async(assembleSkillNode))
                    .addEdge(StateGraph.START, "decomposeAndResolve")
                    .addEdge("decomposeAndResolve", "generateSteps")
                    .addEdge("generateSteps", "assembleSkill")
                    .addEdge("assembleSkill", StateGraph.END);

            CompiledGraph compiledGraph = stateGraph.compile();
            log.info("★ 三阶段合并 Skill Generation Graph V2 编译完成");
            return compiledGraph;
        } catch (GraphStateException e) {
            log.error("Graph V2 编译失败", e);
            throw new RuntimeException("Skill Generation Graph V2 初始化失败", e);
        }
    }
}
