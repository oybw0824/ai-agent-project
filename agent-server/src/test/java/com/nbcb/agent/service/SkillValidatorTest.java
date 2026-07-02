package com.nbcb.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillValidator 单元测试
 * <p>
 * 校验 Skill Markdown 格式是否包含所有必需 Section。
 *
 * @author com.nbcb
 */
@DisplayName("SkillValidator 单元测试")
class SkillValidatorTest {

    private SkillValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SkillValidator();
    }

    @Test
    @DisplayName("包含所有必需 Section → 校验通过")
    void shouldPassWhenAllSectionsPresent() {
        String markdown = """
                ## Name
                weather-query
                
                ## Description
                查询城市天气
                
                ## Inputs
                | 参数名 | 类型 | 必填 | 描述 |
                |--------|------|------|------|
                | city   | string | 是 | 城市名称 |
                
                ## Workflow
                1. 解析城市名称
                2. 调用天气查询工具
                
                ## MCP Tool Calls
                - weather-query：查询天气
                
                ## Error Handling
                城市不存在时返回提示
                
                ## Output
                返回天气信息 JSON
                """;

        SkillValidator.ValidationResult result = validator.validate(markdown);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("缺少 Workflow Section → 校验失败")
    void shouldFailWhenMissingWorkflow() {
        String markdown = """
                ## Name
                test-skill
                
                ## Description
                测试技能
                
                ## Inputs
                无
                
                ## MCP Tool Calls
                无
                
                ## Error Handling
                无
                
                ## Output
                无
                """;

        SkillValidator.ValidationResult result = validator.validate(markdown);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Workflow"));
    }

    @Test
    @DisplayName("空内容 → 校验失败")
    void shouldFailForEmptyContent() {
        SkillValidator.ValidationResult result = validator.validate("");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Markdown 内容为空");
    }

    @Test
    @DisplayName("null 内容 → 校验失败")
    void shouldFailForNullContent() {
        SkillValidator.ValidationResult result = validator.validate(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("Markdown 内容为空");
    }

    @Test
    @DisplayName("缺少多个 Section → 返回所有缺失项")
    void shouldReportAllMissingSections() {
        String markdown = """
                ## Name
                test-skill
                """;

        SkillValidator.ValidationResult result = validator.validate(markdown);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(result.errors()).anyMatch(e -> e.contains("Description"));
        assertThat(result.errors()).anyMatch(e -> e.contains("Workflow"));
    }
}