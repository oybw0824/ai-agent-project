package com.nbcb.mcpclient.controller;

import com.nbcb.mcpclient.domain.ToolCallRequest;
import com.nbcb.mcpclient.domain.ToolCallResponse;
import com.nbcb.mcpclient.domain.ToolMetadata;
import com.nbcb.mcpclient.service.McpToolService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 工具验证接口。
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpToolController {

    private final McpToolService toolService;

    public McpToolController(McpToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/tools")
    public List<ToolMetadata> listTools() {
        return toolService.listTools();
    }

    @PostMapping("/tools/{toolName}/call")
    public ToolCallResponse callTool(
            @PathVariable String toolName,
            @RequestBody(required = false) ToolCallRequest request) {
        ToolCallRequest normalized = request == null
                ? new ToolCallRequest(null)
                : request;
        return toolService.callTool(toolName, normalized.arguments());
    }
}
