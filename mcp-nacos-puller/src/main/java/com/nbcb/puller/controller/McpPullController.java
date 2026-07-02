package com.nbcb.puller.controller;

import com.nbcb.puller.domain.McpPullResponse;
import com.nbcb.puller.service.NacosMcpPullerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpPullController {

    private final NacosMcpPullerService mcpPullerService;

    public McpPullController(NacosMcpPullerService mcpPullerService) {
        this.mcpPullerService = mcpPullerService;
    }

    /**
     * 拉取 Nacos 中所有注册的 MCP 服务
     *
     * @return 拉取结果，包含所有 MCP 服务的详细信息
     */
    @GetMapping("/pull-all")
    public ResponseEntity<McpPullResponse> pullAllMcpServices() {
        McpPullResponse result = mcpPullerService.pullAllMcpServices();
        return ResponseEntity.ok(result);
    }

    /**
     * 健康检查端点
     *
     * @return 健康状态
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }
}