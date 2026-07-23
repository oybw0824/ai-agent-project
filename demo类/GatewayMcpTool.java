package com.example.aigateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record GatewayMcpTool(String name, String description, JsonNode inputSchema) {
}
