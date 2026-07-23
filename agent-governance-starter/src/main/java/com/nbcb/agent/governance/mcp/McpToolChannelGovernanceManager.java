package com.nbcb.agent.governance.mcp;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * MCP 工具渠道治理配置管理器。
 */
@Slf4j
public class McpToolChannelGovernanceManager {

    private static final String STATUS_DISABLED = "DISABLED";
    private static final List<DateTimeFormatter> TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private record BlockKey(String mcpServerName,
                            String toolName,
                            String channelCode) {
    }

    private final McpToolChannelBlockMapper mapper;
    private final McpToolChannelGovernanceProperties properties;
    private final String mcpServerName;

    private LoadingCache<BlockKey, Optional<McpToolChannelBlockEntity>> blockCache;

    public McpToolChannelGovernanceManager(
            McpToolChannelBlockMapper mapper,
            McpToolChannelGovernanceProperties properties,
            String mcpServerName) {
        this.mapper = mapper;
        this.properties = properties;
        this.mcpServerName = mcpServerName;
    }

    @PostConstruct
    public void initCache() {
        int ttl = Math.max(properties.getCacheTtlSeconds(), 5);
        int max = Math.max(properties.getCacheMaxSize(), 100);
        this.blockCache = Caffeine.newBuilder()
                .expireAfterWrite(ttl, TimeUnit.SECONDS)
                .maximumSize(max)
                .build(this::loadBlock);
        log.info("MCP 工具渠道治理缓存初始化完成：TTL={}s, max={}", ttl, max);
    }

    public McpToolChannelGovernanceDecision check(String toolName,
                                                  String channelCode) {
        if (!properties.isEnabled()
                || !StringUtils.hasText(toolName)
                || !StringUtils.hasText(channelCode)) {
            return McpToolChannelGovernanceDecision.allowed();
        }
        Optional<McpToolChannelBlockEntity> block =
                blockCache.get(new BlockKey(mcpServerName, toolName, channelCode));
        if (block.isPresent()
                && isActive(block.get())
                && STATUS_DISABLED.equalsIgnoreCase(block.get().getStatus())) {
            String message = StringUtils.hasText(block.get().getMessage())
                    ? block.get().getMessage()
                    : properties.getUnavailableMessage();
            return McpToolChannelGovernanceDecision.blocked(message);
        }
        return McpToolChannelGovernanceDecision.allowed();
    }

    public void refreshAll() {
        blockCache.invalidateAll();
    }

    private Optional<McpToolChannelBlockEntity> loadBlock(BlockKey key) {
        List<McpToolChannelBlockEntity> candidates =
                mapper.findCandidates(key.toolName(), key.channelCode());
        return candidates.stream()
                .filter(entity -> matchesServerName(entity, key.mcpServerName()))
                .min(Comparator.comparing(entity ->
                        exactServerName(entity, key.mcpServerName()) ? 0 : 1));
    }

    private boolean matchesServerName(McpToolChannelBlockEntity entity,
                                      String currentServerName) {
        if (!StringUtils.hasText(entity.getMcpServerName())) {
            return true;
        }
        return StringUtils.hasText(currentServerName)
                && entity.getMcpServerName().equals(currentServerName);
    }

    private boolean exactServerName(McpToolChannelBlockEntity entity,
                                    String currentServerName) {
        return StringUtils.hasText(currentServerName)
                && currentServerName.equals(entity.getMcpServerName());
    }

    private boolean isActive(McpToolChannelBlockEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveFrom = parseTime(entity.getEffectiveFrom());
        LocalDateTime effectiveTo = parseTime(entity.getEffectiveTo());
        return (effectiveFrom == null || !now.isBefore(effectiveFrom))
                && (effectiveTo == null || !now.isAfter(effectiveTo));
    }

    private LocalDateTime parseTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value.trim(), formatter);
            }
            catch (DateTimeParseException ignored) {
                // 尝试下一个格式。
            }
        }
        log.warn("MCP 工具渠道治理时间格式无法解析，将按已生效处理：{}", value);
        return null;
    }
}
