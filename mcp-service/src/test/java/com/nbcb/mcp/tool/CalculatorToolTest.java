package com.nbcb.mcp.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数值计算器工具 单元测试
 * <p>
 * 验证：加减、乘除、括号优先级、除零、非法字符、空输入
 *
 * @author com.nbcb
 */
@DisplayName("CalculatorTool 单元测试")
class CalculatorToolTest {

    private final CalculatorTool calculatorTool = new CalculatorTool();

    @Test
    @DisplayName("简单加法: 2+3=5")
    void shouldAdd() {
        String result = calculatorTool.calculate("2+3");
        assertThat(result).contains("result").contains("5");
    }

    @Test
    @DisplayName("乘除优先级: 2+3*4=14")
    void shouldHonorPrecedence() {
        String result = calculatorTool.calculate("2+3*4");
        assertThat(result).contains("result").contains("14");
    }

    @Test
    @DisplayName("括号优先级: (1+2)*3=9")
    void shouldHonorParentheses() {
        String result = calculatorTool.calculate("(1+2)*3");
        assertThat(result).contains("result").contains("9");
    }

    @Test
    @DisplayName("除法: 100/4=25")
    void shouldDivide() {
        String result = calculatorTool.calculate("100/4");
        assertThat(result).contains("result").contains("25");
    }

    @Test
    @DisplayName("除零 → 抛出异常")
    void shouldRejectDivisionByZero() {
        assertThatThrownBy(() -> calculatorTool.calculate("1/0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("除数不能为零");
    }

    @Test
    @DisplayName("非法字符 → 抛出异常")
    void shouldRejectInvalidCharacters() {
        assertThatThrownBy(() -> calculatorTool.calculate("2+abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法字符");
    }

    @Test
    @DisplayName("空输入 → 抛出异常")
    void shouldRejectBlankInput() {
        assertThatThrownBy(() -> calculatorTool.calculate(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");

        assertThatThrownBy(() -> calculatorTool.calculate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
