package com.nbcb.mcpserver.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 本地 AI 网关验证状态接口。
 */
@RestController
@RequestMapping("/api/v1/validation")
public class ValidationStatusController {

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "mode", "ai-gateway",
                "protocolVersion", "2025-11-25",
                "jwtValidation", false,
                "mcpEndpoints", List.of("/mcp", "/mcp/mcp-service"),
                "tools", List.of("calculate", "getWeatherByCity"));
    }
}
