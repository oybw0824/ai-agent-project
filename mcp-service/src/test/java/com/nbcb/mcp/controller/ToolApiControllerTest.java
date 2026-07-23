package com.nbcb.mcp.controller;

import com.nbcb.mcp.tool.CalculatorTool;
import com.nbcb.mcp.tool.WeatherTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Higress HTTP 工具接口测试。
 */
@WebMvcTest(ToolApiController.class)
class ToolApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherTool weatherTool;

    @MockitoBean
    private CalculatorTool calculatorTool;

    @Test
    @DisplayName("天气接口返回 JSON")
    void shouldExposeWeatherApi() throws Exception {
        when(weatherTool.getWeatherByCity("北京"))
                .thenReturn("{\"city\":\"北京\",\"temperature\":\"26℃\"}");

        mockMvc.perform(get("/api/tools/weather").param("city", "北京"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"city\":\"北京\",\"temperature\":\"26℃\"}"));
    }

    @Test
    @DisplayName("计算接口返回 JSON")
    void shouldExposeCalculateApi() throws Exception {
        when(calculatorTool.calculate("2+3*4"))
                .thenReturn("{\"expression\":\"2+3*4\",\"result\":14.0}");

        mockMvc.perform(get("/api/tools/calculate").param("expression", "2+3*4"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"expression\":\"2+3*4\",\"result\":14.0}"));
    }
}
