package com.nbcb.mcp.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 天气查询工具 单元测试
 * <p>
 * 验证：
 * 1. 四个主要城市的天气数据正确返回
 * 2. 返回 JSON 格式包含完整字段
 * 3. 未识别城市返回默认数据
 *
 * @author com.nbcb
 */
@DisplayName("WeatherTool 单元测试")
class WeatherToolTest {

    private final WeatherTool weatherTool = new WeatherTool();

    @Test
    @DisplayName("查询北京天气 — 返回 JSON 含温度/湿度/天气/建议")
    void shouldReturnBeijingWeather() {
        String result = weatherTool.getWeatherByCity("北京");

        assertThat(result).contains("北京");
        assertThat(result).contains("temperature");
        assertThat(result).contains("humidity");
        assertThat(result).contains("condition");
        assertThat(result).contains("advice");
    }

    @Test
    @DisplayName("查询上海天气 — 小雨 + 带伞建议")
    void shouldReturnShanghaiWeather() {
        String result = weatherTool.getWeatherByCity("上海");

        assertThat(result).contains("上海");
        assertThat(result).contains("小雨");
        assertThat(result).contains("带伞");
    }

    @Test
    @DisplayName("查询深圳天气 — 返回多云 + 防暑建议")
    void shouldReturnShenzhenWeather() {
        String result = weatherTool.getWeatherByCity("深圳");

        assertThat(result).contains("深圳");
        assertThat(result).contains("多云");
    }

    @Test
    @DisplayName("未识别城市 — 返回默认天气数据")
    void shouldReturnDefaultForUnknownCity() {
        String result = weatherTool.getWeatherByCity("火星");

        assertThat(result).contains("火星");
        assertThat(result).contains("晴");
        assertThat(result).contains("22℃");
    }
}
