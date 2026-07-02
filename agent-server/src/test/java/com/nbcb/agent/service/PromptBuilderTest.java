package com.nbcb.agent.service;

import com.nbcb.agent.domain.Workflow;
import com.nbcb.agent.domain.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptBuilder 单元测试
 * <p>
 * PromptBuilder 是纯字符串拼接组件，无需依赖外部服务，可全量单元测试。
 *
 * @author com.nbcb
 */
@DisplayName("PromptBuilder 单元测试")
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @Test
    @DisplayName("构建完整 Prompt 包含所有必需 Section")
    void shouldBuildCompletePrompt() {
        String prd = "开发一个查询城市天气的技能，用户输入城市名称，返回当前天气信息。";
        String catalog = "[]";
        Workflow workflow = Workflow.builder()
                .name("天气查询")
                .description("查询指定城市当前天气")
                .steps(List.of(
                        WorkflowStep.builder()
                                .stepNumber(1)
                                .goal("解析用户输入的城市名称")
                                .toolName("weather-query")
                                .toolInput("城市名称 string")
                                .toolOutput("天气信息 JSON")
                                .nextStep("结束")
                                .build()
                ))
                .build();

        String result = promptBuilder.build(prd, catalog, workflow, null);

        assertThat(result).contains("PRD 需求文档");
        assertThat(result).contains("开发一个查询城市天气");
        assertThat(result).contains("MCP Tool Catalog");
        assertThat(result).contains("规划的 Workflow");
        assertThat(result).contains("天气查询");
        assertThat(result).contains("解析用户输入的城市名称");
        assertThat(result).contains("Skill 格式要求");
    }

    @Test
    @DisplayName("使用用户提供的模板时，模板包含在 Prompt 中")
    void shouldUseCustomTemplateWhenProvided() {
        String prd = "简单需求";
        String catalog = "[]";
        Workflow workflow = Workflow.builder()
                .name("Test")
                .description("Test")
                .steps(List.of())
                .build();
        String template = "# 自定义模板\n\n这是我的自定义格式要求";

        String result = promptBuilder.build(prd, catalog, workflow, template);

        assertThat(result).contains("## Skill 模板");
        assertThat(result).contains("自定义模板");
        assertThat(result).contains("这是我的自定义格式要求");
    }

    @Test
    @DisplayName("不提供模板时，使用默认格式要求")
    void shouldUseDefaultFormatWhenNoTemplate() {
        String prd = "简单需求";
        String catalog = "[]";
        Workflow workflow = Workflow.builder()
                .name("Test")
                .description("Test")
                .steps(List.of())
                .build();

        String result = promptBuilder.build(prd, catalog, workflow, null);

        assertThat(result).contains("## Skill 格式要求");
        assertThat(result).contains("Name");
        assertThat(result).contains("Description");
        assertThat(result).contains("Workflow");
        assertThat(result).contains("MCP Tool Calls");
        assertThat(result).contains("Error Handling");
        assertThat(result).contains("Output");
    }
}