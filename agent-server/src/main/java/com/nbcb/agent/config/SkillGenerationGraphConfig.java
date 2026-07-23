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
import com.nbcb.agent.service.DecomposeAndResolveService;
import com.nbcb.agent.service.SkillAssemblyEngine;
import com.nbcb.agent.service.SkillValidator;
import com.nbcb.agent.service.SingleStepGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill Generation 三阶段 Graph 配置
 * <pre>
 * START → decomposeAndResolve → generateSteps → assembleSkill → END
 *     （拆解+工具映射合并）      步骤Markdown      组装+校验
 * </pre>
 *
 * @author com.nbcb
 */
@Slf4j
@Configuration
public class SkillGenerationGraphConfig {

    public static final String KEY_PRD_CONTENT = "prdContent";
    public static final String KEY_MCP_CATALOG = "mcpCatalog";
    public static final String TEMPLATE = "template";
    public static final String KEY_TOOL_RESOLUTION_JSON = "toolResolutionJson";
    public static final String KEY_STEP_MARKDOWNS = "stepMarkdowns";
    public static final String KEY_SKILL_MARKDOWN = "skillMarkdown";
    public static final String KEY_VALID = "valid";
    public static final String KEY_VALIDATION_ERRORS = "validationErrors";
    public static final String KEY_PROGRESS_ID = "progressId";

    @Bean
    public KeyStrategyFactory skillGenerationKeyStrategy() {
        return () -> {
            Map<String, KeyStrategy> map = new HashMap<>();
            map.put(KEY_PRD_CONTENT, new ReplaceStrategy());
            map.put(KEY_MCP_CATALOG, new ReplaceStrategy());
            map.put(TEMPLATE, new ReplaceStrategy());
            map.put(KEY_TOOL_RESOLUTION_JSON, new ReplaceStrategy());
            map.put(KEY_STEP_MARKDOWNS, new ReplaceStrategy());
            map.put(KEY_SKILL_MARKDOWN, new ReplaceStrategy());
            map.put(KEY_VALID, new ReplaceStrategy());
            map.put(KEY_VALIDATION_ERRORS, new ReplaceStrategy());
            map.put(KEY_PROGRESS_ID, new ReplaceStrategy());
            return map;
        };
    }

    // ==================== 三阶段 Graph ====================

    @Bean
    public CompiledGraph skillGenerationGraph(
            KeyStrategyFactory keyStrategyFactory,
            DecomposeAndResolveService decomposeAndResolveService,
            SingleStepGenerationService stepGenerationService,
            SkillAssemblyEngine assemblyEngine,
            SkillValidator validator,
            ApplicationEventPublisher eventPublisher) {

        NodeAction node1 = (state) -> {
            String prd = (String) state.value(KEY_PRD_CONTENT).orElseThrow();
            String catalog = (String) state.value(KEY_MCP_CATALOG).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            publishEvent(eventPublisher, progressId, "decomposeAndResolve",
                    StageProgressEvent.Status.START, null);
            String result = decomposeAndResolveService.execute(prd, catalog);
            publishEvent(eventPublisher, progressId, "decomposeAndResolve",
                    StageProgressEvent.Status.COMPLETE, "拆解+映射完成，JSON=" + result.length() + "字符");
            log.info("★ [decomposeAndResolve] 完成");
            return Map.of(KEY_TOOL_RESOLUTION_JSON, result);
        };

        NodeAction node2 = (state) -> {
            String toolJson = (String) state.value(KEY_TOOL_RESOLUTION_JSON).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            publishEvent(eventPublisher, progressId, "generateSteps",
                    StageProgressEvent.Status.START, null);
            List<String> stepMarkdowns = stepGenerationService.generateAllSteps(toolJson, progressId);
            publishEvent(eventPublisher, progressId, "generateSteps",
                    StageProgressEvent.Status.COMPLETE, "生成" + stepMarkdowns.size() + "个步骤");
            log.info("★ [generateSteps] 完成 — {}个步骤", stepMarkdowns.size());
            return Map.of(KEY_STEP_MARKDOWNS, stepMarkdowns);
        };

        NodeAction node3 = (state) -> {
            String toolJson = (String) state.value(KEY_TOOL_RESOLUTION_JSON).orElseThrow();
            @SuppressWarnings("unchecked")
            List<String> stepMarkdowns = (List<String>) state.value(KEY_STEP_MARKDOWNS).orElseThrow();
            String progressId = (String) state.value(KEY_PROGRESS_ID).orElse(null);
            publishEvent(eventPublisher, progressId, "assembleSkill",
                    StageProgressEvent.Status.START, null);

            String skillMarkdown = assemblyEngine.assemble(toolJson, stepMarkdowns);
            SkillValidator.ValidationResult vr = validator.validate(skillMarkdown);
            publishEvent(eventPublisher, progressId, "assembleSkill",
                    StageProgressEvent.Status.COMPLETE,
                    "SKILL.md=" + skillMarkdown.length() + "字符，校验=" + (vr.valid() ? "通过" : "失败"));
            log.info("★ [assembleSkill] 校验={}", vr.valid() ? "通过" : "失败");
            return Map.of(KEY_SKILL_MARKDOWN, skillMarkdown, KEY_VALID, vr.valid(),
                    KEY_VALIDATION_ERRORS, vr.errors());
        };

        try {
            CompiledGraph graph = new StateGraph(keyStrategyFactory)
                    .addNode("decomposeAndResolve", AsyncNodeAction.node_async(node1))
                    .addNode("generateSteps", AsyncNodeAction.node_async(node2))
                    .addNode("assembleSkill", AsyncNodeAction.node_async(node3))
                    .addEdge(StateGraph.START, "decomposeAndResolve")
                    .addEdge("decomposeAndResolve", "generateSteps")
                    .addEdge("generateSteps", "assembleSkill")
                    .addEdge("assembleSkill", StateGraph.END)
                    .compile();
            log.info("★ 三阶段 Skill Generation Graph 编译完成");
            return graph;
        } catch (GraphStateException e) {
            throw new RuntimeException("Skill Generation Graph 初始化失败", e);
        }
    }

    private static void publishEvent(ApplicationEventPublisher publisher, String progressId,
                                      String stage, StageProgressEvent.Status status, String detail) {
        if (progressId == null || publisher == null) return;
        publisher.publishEvent(new StageProgressEvent(progressId, stage, status, detail));
    }
}
