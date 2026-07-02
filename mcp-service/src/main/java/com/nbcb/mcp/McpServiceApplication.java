package com.nbcb.mcp;

import com.nbcb.mcp.tool.CalculatorTool;
import com.nbcb.mcp.tool.WeatherTool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * MCP 工具服务端 主启动类
 * <p>
 * Nacos MCP Starter 自动注册工具到 Nacos 3.2 AI Registry。
 *
 * @author com.nbcb
 */
@SpringBootApplication
public class McpServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServiceApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider mcpTools(WeatherTool weatherTool, CalculatorTool calculatorTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(weatherTool, calculatorTool)
                .build();
    }
}
