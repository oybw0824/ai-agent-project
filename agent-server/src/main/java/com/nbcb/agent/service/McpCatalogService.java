package com.nbcb.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool Catalog 公共服务
 * <p>
 * 统一提供 MCP 工具清单的元数据查询和 JSON 序列化，
 * 消除 {@link com.nbcb.agent.controller.ChatController} 和
 * {@link com.nbcb.agent.controller.SkillGenerationController} 中的重复逻辑。
 * <p>
 * ★ 优化：启动时构建工具目录快照，避免每次请求重复遍历和 JSON 序列化。
 * 工具列表在运行时不变（MCP 工具注册是启动时一次性完成的），因此缓存是安全的。
 *
 * @author com.nbcb
 */
@Slf4j
@Service
public class McpCatalogService {

    private final ToolCallbackProvider mcpToolProvider;
    private final ObjectMapper objectMapper;

    /** ★ 工具元数据列表缓存（启动时构建，不可变） */
    private volatile List<Map<String, Object>> cachedToolMetadata = Collections.emptyList();

    /** ★ 工具目录 JSON 缓存（启动时序列化一次） */
    private volatile String cachedCatalogJson = "[]";

    public McpCatalogService(@Autowired(required = false) ToolCallbackProvider mcpToolProvider,
                             ObjectMapper objectMapper) {
        this.mcpToolProvider = mcpToolProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * ★ 启动时构建工具目录缓存，后续请求直接返回缓存
     */
    @PostConstruct
    public void init() {
        refreshCache();
        log.info("★ MCP Tool Catalog 缓存初始化完成：{} 个工具", cachedToolMetadata.size());
    }

    /**
     * 获取所有已注册 MCP 工具的元数据列表
     *
     * @return 工具元数据列表（含来源标记）
     */
    public List<Map<String, Object>> listToolMetadata() {
        return cachedToolMetadata;
    }

    /**
     * 构建 MCP Tool Catalog JSON 字符串（供 Skill Generation 各阶段使用）
     *
     * @return 格式化 JSON 字符串
     */
    public String buildCatalogJson() {
        return cachedCatalogJson;
    }

    /**
     * 是否有可用的 MCP 工具
     */
    public boolean hasTools() {
        return mcpToolProvider != null && mcpToolProvider.getToolCallbacks().length > 0;
    }

    /**
     * ★ 刷新缓存（当 MCP 工具动态变化时调用）
     */
    private void refreshCache() {
        List<Map<String, Object>> tools = new ArrayList<>();

        if (mcpToolProvider != null) {
            for (ToolCallback tc : mcpToolProvider.getToolCallbacks()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", tc.getToolDefinition().name());
                tool.put("description", tc.getToolDefinition().description());
                tool.put("inputSchema", tc.getToolDefinition().inputSchema());
                tool.put("source", "Higress");
                tool.put("sourceDetail", "mcp-service (HTTP → Higress → Streamable MCP)");
                tools.add(tool);
            }
        }

        cachedToolMetadata = Collections.unmodifiableList(tools);
        try {
            cachedCatalogJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools);
        } catch (JsonProcessingException e) {
            log.warn("★ 构建 MCP Catalog JSON 失败: {}", e.getMessage());
            cachedCatalogJson = "[]";
        }
    }
}
