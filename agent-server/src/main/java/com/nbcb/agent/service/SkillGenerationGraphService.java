package com.nbcb.agent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.nbcb.agent.config.SkillGenerationGraphConfig;
import com.nbcb.agent.domain.SkillGenerationRequest;
import com.nbcb.agent.domain.SkillGenerationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill Generation 基于 Spring AI Alibaba Graph 框架的编排服务
 * <p>
 * 使用官方 {@link CompiledGraph} API：
 * <ul>
 *   <li>流程定义在 {@link com.nbcb.agent.config.SkillGenerationGraphConfig}</li>
 *   <li>每个步骤是独立 {@link com.alibaba.cloud.ai.graph.Node}</li>
 *   <li>结果通过 {@link OverAllState} 在节点间传递</li>
 *   <li>调用 {@link CompiledGraph#invoke(Map)} 执行整个流程</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class SkillGenerationGraphService {

    private final CompiledGraph skillGraph;

    public SkillGenerationGraphService(CompiledGraph skillGraph) {
        this.skillGraph = skillGraph;
        log.info("★ SkillGenerationGraphService 初始化完成");
    }

    /**
     * 使用 Graph 流程生成 Skill Markdown
     *
     * @param request 生成请求
     * @return 生成结果
     */
    public SkillGenerationResponse generate(SkillGenerationRequest request) {
        return doGenerate(request, null);
    }

    /**
     * 带进度追踪的生成 — 给 SSE 流式推送使用
     */
    public SkillGenerationResponse generateWithProgress(SkillGenerationRequest request, String progressId) {
        return doGenerate(request, progressId);
    }

    /**
     * ★ 统一生成入口 — 消除 generate() 和 generateWithProgress() 的重复代码
     */
    private SkillGenerationResponse doGenerate(SkillGenerationRequest request, String progressId) {
        long startTime = System.currentTimeMillis();
        log.info("★ Skill Generation (Graph) 开始 — PRD 长度={} 字符{}",
                request.getPrdContent().length(),
                progressId != null ? ", progressId=" + progressId : "");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put(SkillGenerationGraphConfig.KEY_PRD_CONTENT, request.getPrdContent());
        input.put(SkillGenerationGraphConfig.KEY_MCP_CATALOG, request.getMcpCatalog());
        if (progressId != null) {
            input.put(SkillGenerationGraphConfig.KEY_PROGRESS_ID, progressId);
        }
        if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            input.put(SkillGenerationGraphConfig.TEMPLATE, request.getTemplate());
        }

        OverAllState finalState = skillGraph.invoke(input).orElseThrow(
                () -> new IllegalStateException("Graph 执行失败，未返回最终状态")
        );

        long processingTime = System.currentTimeMillis() - startTime;

        String skillMarkdown = (String) finalState.value(SkillGenerationGraphConfig.KEY_SKILL_MARKDOWN).orElse(null);
        Boolean valid = (Boolean) finalState.value(SkillGenerationGraphConfig.KEY_VALID).orElse(false);
        @SuppressWarnings("unchecked")
        java.util.List<String> validationErrors = (java.util.List<String>)
                finalState.value(SkillGenerationGraphConfig.KEY_VALIDATION_ERRORS).orElse(Collections.emptyList());

        log.info("★ Skill Generation (Graph) 完成 — {}ms, 校验={}",
                processingTime, valid ? "通过" : "失败");

        return SkillGenerationResponse.builder()
                .skillMarkdown(skillMarkdown)
                .valid(valid)
                .validationErrors(validationErrors)
                .processingTimeMs(processingTime)
                .build();
    }
}
