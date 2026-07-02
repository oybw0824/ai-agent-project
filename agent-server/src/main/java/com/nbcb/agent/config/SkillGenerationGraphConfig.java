package com.nbcb.agent.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.nbcb.agent.metric.AgentMetrics;
import com.nbcb.agent.service.DecomposePrdService;
import com.nbcb.agent.service.ToolResolutionService;
import com.nbcb.agent.service.SingleStepGenerationService;
import com.nbcb.agent.service.SkillAssemblyService;
import com.nbcb.agent.service.SkillGenerationProgress;
import com.nbcb.agent.service.SkillValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            strategyMap.put(KEY_SKILL_MARKDOWN, new ReplaceStrategy());
            strategyMap.put(KEY_VALID, new ReplaceStrategy());
            strategyMap.put(KEY_VALIDATION_ERRORS, new ReplaceStrategy());
            strategyMap.put(KEY_PROGRESS_ID, new ReplaceStrategy());
            return strategyMap;
        };
    }

    /**
     * 节点 1：阶段一 — PRD 步骤拆解
     */
    @Bean
    public NodeAction decomposePrdNode(DecomposePrdService service, AgentMetrics metrics) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String prd = (String) state.value(KEY_PRD_CONTENT).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(progressId, "decomposePrd", null);
            String decompositionJson = service.decompose(prd);
            reportStage(progressId, "decomposePrd", "拆解完成，JSON长度=" + decompositionJson.length() + "字符");
            metrics.skillGenPhaseDecompose.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);
            log.info("★ Graph Node [decomposePrd] 完成 — 阶段一拆解完毕");
            return Map.of(KEY_DECOMPOSITION_JSON, decompositionJson);
        };
    }

    /**
     * 节点 2：阶段二 — MCP 工具映射
     */
    @Bean
    public NodeAction resolveToolsNode(ToolResolutionService service, AgentMetrics metrics) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String decompositionJson = (String) state.value(KEY_DECOMPOSITION_JSON).orElseThrow();
            String mcpCatalog = (String) state.value(KEY_MCP_CATALOG).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(progressId, "resolveTools", null);
            String toolResolutionJson = service.resolve(decompositionJson, mcpCatalog);
            reportStage(progressId, "resolveTools", "工具映射完成，JSON长度=" + toolResolutionJson.length() + "字符");
            metrics.skillGenPhaseToolMap.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);
            log.info("★ Graph Node [resolveTools] 完成 — 阶段二工具映射完毕");
            return Map.of(KEY_TOOL_RESOLUTION_JSON, toolResolutionJson);
        };
    }

    /**
     * 节点 3：阶段三 — 单步骤标准化生成（批量 + 并行）
     */
    @Bean
    public NodeAction generateStepsNode(SingleStepGenerationService service, AgentMetrics metrics) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String toolResolutionJson = (String) state.value(KEY_TOOL_RESOLUTION_JSON).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(progressId, "generateSteps", null);
            List<String> stepMarkdowns = service.generateAllSteps(toolResolutionJson);
            reportStage(progressId, "generateSteps", "生成" + stepMarkdowns.size() + "个步骤Markdown");
            metrics.skillGenPhaseStepGen.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);
            log.info("★ Graph Node [generateSteps] 完成 — 生成 {} 个步骤 Markdown", stepMarkdowns.size());
            return Map.of(KEY_STEP_MARKDOWNS, stepMarkdowns);
        };
    }

    /**
     * 节点 4：阶段四 — Skill 组装与校验
     */
    @Bean
    public NodeAction assembleSkillNode(SkillAssemblyService assemblyService, SkillValidator validator,
                                         AgentMetrics metrics) {
        return (state) -> {
            long t0 = System.currentTimeMillis();
            String decompositionJson = (String) state.value(KEY_DECOMPOSITION_JSON).orElseThrow();
            String toolResolutionJson = (String) state.value(KEY_TOOL_RESOLUTION_JSON).orElseThrow();
            @SuppressWarnings("unchecked")
            List<String> stepMarkdowns = (List<String>) state.value(KEY_STEP_MARKDOWNS).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            reportStage(progressId, "assembleSkill", null);

            String skillMarkdown = assemblyService.assemble(
                    decompositionJson, toolResolutionJson, stepMarkdowns);
            log.info("★ Graph Node [assembleSkill] 组装完成 — 长度 {} 字符", skillMarkdown.length());

            SkillValidator.ValidationResult validationResult = validator.validate(skillMarkdown);
            log.info("★ Graph Node [assembleSkill] 校验={}",
                    validationResult.valid() ? "通过" : "失败");

            reportStage(progressId, "assembleSkill",
                    "SKILL.md长度=" + skillMarkdown.length() + "字符，校验=" + (validationResult.valid() ? "通过" : "失败"));

            metrics.skillGenPhaseAssembly.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS);

            return Map.of(
                    KEY_SKILL_MARKDOWN, skillMarkdown,
                    KEY_VALID, validationResult.valid(),
                    KEY_VALIDATION_ERRORS, validationResult.errors()
            );
        };
    }

    /** 上报阶段进度 */
    private static void reportStage(String progressId, String stageName, String detail) {
        if (progressId == null) return;
        SkillGenerationProgress progress = SkillGenerationProgress.get(progressId);
        if (progress == null) return;
        if (detail == null) {
            progress.stageStart(stageName);
        } else {
            progress.stageComplete(stageName, detail);
        }
    }

    /**
     * 构建并编译四阶段 Skill Generation Graph
     */
    @Bean
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
}