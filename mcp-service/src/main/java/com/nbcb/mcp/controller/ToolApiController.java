package com.nbcb.mcp.controller;

import com.nbcb.mcp.tool.CalculatorTool;
import com.nbcb.mcp.tool.WeatherTool;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 Higress 转换为 MCP Tools 的 HTTP API。
 *
 * @author com.nbcb
 */
@RestController
@RequestMapping(value = "/api/tools", produces = MediaType.APPLICATION_JSON_VALUE)
public class ToolApiController {

    private final WeatherTool weatherTool;
    private final CalculatorTool calculatorTool;

    public ToolApiController(WeatherTool weatherTool, CalculatorTool calculatorTool) {
        this.weatherTool = weatherTool;
        this.calculatorTool = calculatorTool;
    }

    /**
     * 根据城市名称查询模拟天气。
     */
    @GetMapping("/weather")
    public String weather(@RequestParam String city) {
        return weatherTool.getWeatherByCity(city);
    }

    /**
     * 执行安全的四则运算表达式。
     */
    @GetMapping("/calculate")
    public String calculate(@RequestParam String expression) {
        return calculatorTool.calculate(expression);
    }
}
