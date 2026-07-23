package com.nbcb.mcpserver.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 网关 HTTP 后端业务单元测试。
 */
class ValidationToolsTest {

    private final ValidationTools tools = new ValidationTools();

    @Test
    void shouldCalculateExpression() {
        ValidationTools.CalculationResult result = tools.calculate("(12+8)*3");
        assertThat(result.result()).isEqualTo(60.0);
    }

    @Test
    void shouldRejectUnsafeExpression() {
        assertThatThrownBy(() -> tools.calculate("Runtime.exec('x')"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法字符");
    }

    @Test
    void shouldReturnWeather() {
        ValidationTools.WeatherResult result = tools.getWeatherByCity("北京");
        assertThat(result.city()).isEqualTo("北京");
        assertThat(result.temperature()).isEqualTo("26℃");
    }
}
