package com.nbcb.agent.governance.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.nbcb.agent.governance.entity.AgentGovernanceEntity;
import com.nbcb.agent.governance.mapper.AgentGovernanceMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ★ Agent 治理配置管理器
 *
 * @author com.nbcb
 */
@Slf4j
public class AgentGovernanceManager {

    private record ScopeKey(String agentName, String scopeId) {}
    private record GlobalScopeKey(String configType, String scopeId) {}

    private final AgentGovernanceMapper mapper;

    @Value("${agent-governance.cache.ttl-seconds:30}")
    private int cacheTtlSeconds;

    @Value("${agent-governance.cache.max-size:2000}")
    private int cacheMaxSize;

    private LoadingCache<String, Optional<AgentGovernanceEntity>> agentCache;
    private LoadingCache<ScopeKey, Optional<AgentGovernanceEntity>> userCache;
    private LoadingCache<ScopeKey, Optional<AgentGovernanceEntity>> orgCache;
    private LoadingCache<GlobalScopeKey, Optional<AgentGovernanceEntity>> globalScopeCache;
    private LoadingCache<String, List<AgentGovernanceEntity>> routeCache;

    public AgentGovernanceManager(AgentGovernanceMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void initCaches() {
        int ttl = Math.max(cacheTtlSeconds, 5);
        int max = Math.max(cacheMaxSize, 100);
        this.agentCache = Caffeine.newBuilder().expireAfterWrite(ttl, TimeUnit.SECONDS).maximumSize(max).build(mapper::findAgentConfig);
        this.userCache  = Caffeine.newBuilder().expireAfterWrite(ttl, TimeUnit.SECONDS).maximumSize(max).build(k -> mapper.findUserWhitelist(k.agentName, k.scopeId));
        this.orgCache   = Caffeine.newBuilder().expireAfterWrite(ttl, TimeUnit.SECONDS).maximumSize(max).build(k -> mapper.findOrgWhitelist(k.agentName, k.scopeId));
        this.globalScopeCache = Caffeine.newBuilder().expireAfterWrite(ttl, TimeUnit.SECONDS).maximumSize(max).build(this::loadGlobalScope);
        this.routeCache = Caffeine.newBuilder().expireAfterWrite(ttl * 2L, TimeUnit.SECONDS).maximumSize(1).build(k -> mapper.findAllRoutes());
        log.info("★ AgentGovernanceManager 初始化完成：TTL={}s, max={}", ttl, max);
    }

    /**
     * ★ 判断指定用户/机构是否可访问指定 Agent（独立校验模式）
     * <p>
     * 规则：
     * <ol>
     *   <li>userId 有值 → 查用户配置，若 DISABLED 且有效 → 拦截</li>
     *   <li>orgId 有值 → 查机构配置，若 DISABLED 且有效 → 拦截</li>
     *   <li>任一维度命中 DISABLED → 拒绝；都通过或无配置 → 全局兜底</li>
     * </ol>
     * <b>传了才校验</b>：userId/orgId 为空时跳过对应维度的检查。
     */
    public boolean isEnabled(String agentName, String userId, String orgId) {
        // ★ 用户维度：传了才校验
        if (userId != null && !userId.isBlank()) {
            Optional<AgentGovernanceEntity> u = userCache.get(new ScopeKey(agentName, userId));
            if (u.isPresent() && isActive(u.get()) && !enabled(u.get())) {
                return false; // 用户黑名单 → 直接拦截
            }
        }

        // ★ 机构维度：传了才校验
        if (orgId != null && !orgId.isBlank()) {
            Optional<AgentGovernanceEntity> o = orgCache.get(new ScopeKey(agentName, orgId));
            if (o.isPresent() && isActive(o.get()) && !enabled(o.get())) {
                return false; // 机构黑名单 → 直接拦截
            }
        }

        // ★ 全局兜底
        return agentCache.get(agentName)
                .map(e -> "ENABLED".equals(e.getDefaultStatus()))
                .orElse(true);
    }

    public boolean isEnabled(String agentName) {
        return agentCache.get(agentName).map(e -> "ENABLED".equals(e.getStatus())).orElse(true);
    }

    public boolean isBlocked(String channelCode, String userId, String orgId) {
        if (isBlocked(AgentGovernanceEntity.TYPE_CHANNEL, channelCode)) {
            return true;
        }
        if (isBlocked(AgentGovernanceEntity.TYPE_USER, userId)) {
            return true;
        }
        return isBlocked(AgentGovernanceEntity.TYPE_ORG, orgId);
    }

    public List<AgentGovernanceEntity> getRoutes() { return routeCache.get("ALL"); }

    public void refreshAgent(String agentName) { agentCache.invalidate(agentName); }
    public void refreshUser(String agentName, String userId) { userCache.invalidate(new ScopeKey(agentName, userId)); }
    public void refreshOrg(String agentName, String orgId) { orgCache.invalidate(new ScopeKey(agentName, orgId)); }
    public void refreshAll() { agentCache.invalidateAll(); userCache.invalidateAll(); orgCache.invalidateAll(); globalScopeCache.invalidateAll(); routeCache.invalidateAll(); }

    private boolean enabled(AgentGovernanceEntity e) { return "ENABLED".equals(e.getStatus()); }
    private boolean disabled(AgentGovernanceEntity e) { return "DISABLED".equals(e.getStatus()); }
    private boolean isActive(AgentGovernanceEntity e) {
        LocalDateTime now = LocalDateTime.now();
        return (e.getEffectiveFrom() == null || !now.isBefore(e.getEffectiveFrom()))
            && (e.getEffectiveTo() == null || !now.isAfter(e.getEffectiveTo()));
    }

    private boolean isBlocked(String configType, String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            return false;
        }
        return globalScopeCache.get(new GlobalScopeKey(configType, scopeId))
                .filter(this::isActive)
                .map(this::disabled)
                .orElse(false);
    }

    private Optional<AgentGovernanceEntity> loadGlobalScope(GlobalScopeKey key) {
        return switch (key.configType()) {
            case AgentGovernanceEntity.TYPE_CHANNEL -> mapper.findGlobalChannelBlock(key.scopeId());
            case AgentGovernanceEntity.TYPE_USER -> mapper.findGlobalUserBlock(key.scopeId());
            case AgentGovernanceEntity.TYPE_ORG -> mapper.findGlobalOrgBlock(key.scopeId());
            default -> Optional.empty();
        };
    }
}
