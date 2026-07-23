package com.nbcb.nacosmcpagent.agent;

import com.nbcb.nacosmcpagent.audit.AgentCallAuditService;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 AI_TOOL 元数据解析为启动时可用的本地或 Nacos MCP ToolCallback。
 */
@Slf4j
public class ConfiguredToolResolver {

    private static final String FALLBACK_INPUT_SCHEMA =
            "{\"type\":\"object\",\"additionalProperties\":true}";

    private final List<ToolCallback> localToolCallbacks;
    private final ObjectProvider<ToolCallbackProvider> remoteToolProvider;
    private final String localMcpCode;
    private final AgentCallAuditService auditService;

    public ConfiguredToolResolver(
            List<ToolCallback> localToolCallbacks,
            @Qualifier("distributedAsyncToolCallback")
            ObjectProvider<ToolCallbackProvider> remoteToolProvider,
            String localMcpCode,
            AgentCallAuditService auditService) {
        this.localToolCallbacks = List.copyOf(localToolCallbacks);
        this.remoteToolProvider = remoteToolProvider;
        this.localMcpCode = localMcpCode;
        this.auditService = auditService;
    }

    public ToolSnapshot snapshot() {
        List<ToolCallback> remote;
        RuntimeException remoteError = null;
        try {
            ToolCallbackProvider provider =
                    remoteToolProvider.getIfAvailable();
            remote = provider == null
                    ? List.of()
                    : Arrays.asList(provider.getToolCallbacks());
        }
        catch (RuntimeException ex) {
            remote = List.of();
            remoteError = ex;
        }
        return new ToolSnapshot(
                localToolCallbacks,
                List.copyOf(remote),
                remoteError);
    }

    public Map<String, List<ToolCallback>> resolveGroupedTools(
            Map<String, List<ToolDefinition>> configuredGroups,
            ToolSnapshot snapshot) {
        return resolveGroupedTools(null, null, configuredGroups, snapshot);
    }

    public Map<String, List<ToolCallback>> resolveGroupedTools(
            String agentId,
            String nodeId,
            Map<String, List<ToolDefinition>> configuredGroups,
            ToolSnapshot snapshot) {
        Map<String, List<ToolCallback>> resolved = new LinkedHashMap<>();
        configuredGroups.forEach((skillCode, definitions) -> resolved.put(
                skillCode,
                definitions.stream()
                        .map(definition -> resolve(definition, snapshot))
                        .map(callback -> auditService.wrapTool(
                                callback, agentId, nodeId))
                        .toList()));
        return Map.copyOf(resolved);
    }

    public List<ToolCallback> resolveTools(
            List<ToolDefinition> definitions,
            ToolSnapshot snapshot) {
        return resolveTools(null, null, definitions, snapshot);
    }

    public List<ToolCallback> resolveTools(
            String agentId,
            String nodeId,
            List<ToolDefinition> definitions,
            ToolSnapshot snapshot) {
        return definitions.stream()
                .map(definition -> resolve(definition, snapshot))
                .map(callback -> auditService.wrapTool(
                        callback, agentId, nodeId))
                .toList();
    }

    private ToolCallback resolve(
            ToolDefinition definition,
            ToolSnapshot snapshot) {
        boolean local = localMcpCode.equalsIgnoreCase(definition.mcpCode());
        List<ToolCallback> pool = local
                ? snapshot.localTools() : snapshot.remoteTools();
        String expectedName = local
                ? definition.toolCode()
                : mcpPrefix(definition.mcpCode())
                + "_" + definition.toolCode();

        List<ToolCallback> exactMatches = pool.stream()
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(expectedName))
                .toList();
        if (exactMatches.size() == 1) {
            return exactMatches.get(0);
        }
        if (exactMatches.size() > 1) {
            throw ambiguousTool(
                    definition, expectedName, exactMatches.size());
        }

        // 不同版本的分布式客户端前缀规则可能不同，后缀唯一时兼容匹配。
        List<ToolCallback> suffixMatches = pool.stream()
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(definition.toolCode())
                        || callback.getToolDefinition().name()
                        .endsWith("_" + definition.toolCode()))
                .toList();
        if (suffixMatches.size() == 1) {
            return suffixMatches.get(0);
        }
        if (suffixMatches.size() > 1) {
            throw ambiguousTool(
                    definition, expectedName, suffixMatches.size());
        }

        if (!local) {
            return unavailableRemoteTool(
                    definition,
                    expectedName,
                    snapshot.remoteError());
        }

        throw new IllegalStateException(
                "AI_TOOL 无法唯一匹配启动时本地工具: mcpCode="
                        + definition.mcpCode()
                        + ", toolCode=" + definition.toolCode()
                        + ", expectedName=" + expectedName
                        + ", matches=" + suffixMatches.size());
    }

    private ToolCallback unavailableRemoteTool(
            ToolDefinition definition,
            String toolName,
            RuntimeException remoteError) {
        String cause = remoteError == null
                ? "启动时未发现远程 MCP 工具"
                : rootMessage(remoteError);
        log.warn("远程 MCP 工具不可用，启动时注册降级工具: mcpCode={}, toolCode={}, toolName={}, cause={}",
                definition.mcpCode(),
                definition.toolCode(),
                toolName,
                cause);
        return new UnavailableRemoteToolCallback(
                toolName,
                definition.mcpCode(),
                definition.toolCode(),
                cause);
    }

    private static IllegalStateException ambiguousTool(
            ToolDefinition definition,
            String expectedName,
            int matches) {
        return new IllegalStateException(
                "AI_TOOL 匹配到多个启动时工具: mcpCode="
                        + definition.mcpCode()
                        + ", toolCode=" + definition.toolCode()
                        + ", expectedName=" + expectedName
                        + ", matches=" + matches);
    }

    private static String mcpPrefix(String mcpCode) {
        String[] segments = mcpCode.toLowerCase(Locale.ROOT)
                .split("[^a-z0-9]+");
        StringBuilder prefix = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            if (!prefix.isEmpty()) {
                prefix.append('_');
            }
            prefix.append(segment.charAt(0));
        }
        return prefix.toString();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public record ToolSnapshot(
            List<ToolCallback> localTools,
            List<ToolCallback> remoteTools,
            RuntimeException remoteError) {
    }

    private record UnavailableRemoteToolCallback(
            String toolName,
            String mcpCode,
            String toolCode,
            String cause) implements ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(toolName)
                    .description("远程 MCP 工具暂不可用。调用后会返回不可用原因，禁止编造工具结果。")
                    .inputSchema(FALLBACK_INPUT_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return """
                    {"available":false,"mcpCode":"%s","toolCode":"%s","message":"远程 MCP 服务不可用或工具未发现，请稍后重试。","cause":"%s"}
                    """.formatted(
                    escapeJson(mcpCode),
                    escapeJson(toolCode),
                    escapeJson(cause));
        }

        @Override
        public String call(
                String toolInput,
                ToolContext toolContext) {
            return call(toolInput);
        }
    }
}
