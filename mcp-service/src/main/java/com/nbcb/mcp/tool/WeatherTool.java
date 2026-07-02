package com.nbcb.mcp.tool;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 城市天气查询 MCP 工具
 * <p>
 * 使用 Spring AI {@code @Tool} 注解将方法暴露为 MCP 工具。
 * 当 MCP Client（agent-server）调用此工具时，自动通过 MCP 协议传输参数和结果。
 * <p>
 * 当前为模拟实现，返回静态天气数据。生产环境可对接真实天气 API（如高德/和风天气）。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class WeatherTool {

    /** 天气数据实体 */
    @Data
    private static class WeatherData {
        private final String temperature;
        private final String humidity;
        private final String condition;
        private final String advice;
    }

    /** 默认天气数据（未知城市时使用） */
    private static final WeatherData DEFAULT_WEATHER = new WeatherData(
            "22℃", "55%", "晴", "天气不错，适合户外活动");

    /** 城市天气数据映射表 */
    private static final Map<String, WeatherData> WEATHER_MAP = Map.of(
            "北京", new WeatherData("26℃", "45%", "晴转多云", "紫外线较强，建议涂抹防晒霜"),
            "上海", new WeatherData("29℃", "70%", "小雨", "有降雨，出门请带伞"),
            "深圳", new WeatherData("32℃", "80%", "多云", "天气炎热，注意防暑降温")
    );

    /**
     * 根据城市名称查询天气情况
     * <p>
     * MCP 工具名称：getWeatherByCity（默认取自方法名）
     *
     * @param city 城市名称，例如"北京"、"上海"、"深圳"
     * @return JSON 格式的天气信息（城市、温度、湿度、天气状况、建议）
     */
    @Tool(description = "查询中国城市的天气情况，返回温度、湿度、天气状况等信息")
    public String getWeatherByCity(
            @ToolParam(description = "城市名称，例如北京、上海、深圳") String city) {

        log.info("MCP 工具[getWeatherByCity]被调用，参数 city={}", city);
        String weatherJson = buildWeatherData(city);
        log.info("MCP 工具[getWeatherByCity]返回结果：{}", weatherJson);
        return weatherJson;
    }

    /**
     * 构建天气数据 JSON
     */
    private String buildWeatherData(String city) {
        WeatherData data = WEATHER_MAP.getOrDefault(city, DEFAULT_WEATHER);
        return String.format(
                "{\"city\":\"%s\",\"temperature\":\"%s\",\"humidity\":\"%s\",\"condition\":\"%s\",\"advice\":\"%s\"}",
                city, data.getTemperature(), data.getHumidity(), data.getCondition(), data.getAdvice());
    }
}
