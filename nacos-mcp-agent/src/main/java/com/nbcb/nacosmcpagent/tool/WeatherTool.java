package com.nbcb.nacosmcpagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 注册到 Nacos AI Registry 的本地天气 MCP 工具。
 */
@Slf4j
@Service
@LocalMcpTool
@ConditionalOnProperty(
        prefix = "mcp.tools.weather",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WeatherTool {

    private static final WeatherData DEFAULT_WEATHER =
            new WeatherData("22℃", "55%", "晴", "适合户外活动");

    private static final Map<String, WeatherData> WEATHER = Map.of(
            "北京", new WeatherData("26℃", "45%", "晴转多云", "注意防晒"),
            "上海", new WeatherData("29℃", "70%", "小雨", "出门请带伞"),
            "深圳", new WeatherData("32℃", "80%", "多云", "注意防暑")
    );

    private final String provider;

    public WeatherTool(
            @Value("${spring.ai.mcp.server.name:nacos-mcp-agent}")
            String provider) {
        this.provider = provider;
    }

    @Tool(description = "查询中国城市的模拟天气，返回温度、湿度、天气状况、建议和提供结果的 MCP 服务名 provider")
    public WeatherResult getWeatherByCity(
            @ToolParam(description = "城市名称，例如北京、上海、深圳")
            String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名称不能为空");
        }
        String normalizedCity = city.trim();
        WeatherData data = WEATHER.getOrDefault(
                normalizedCity, DEFAULT_WEATHER);
        WeatherResult result = new WeatherResult(
                provider,
                normalizedCity,
                data.temperature(),
                data.humidity(),
                data.condition(),
                data.advice());
        log.info("本地天气工具调用完成：provider={}, city={}",
                provider, normalizedCity);
        return result;
    }

    private record WeatherData(
            String temperature,
            String humidity,
            String condition,
            String advice) {
    }

    /**
     * 天气工具的结构化返回结果。
     */
    public record WeatherResult(
            String provider,
            String city,
            String temperature,
            String humidity,
            String condition,
            String advice) {
    }
}
