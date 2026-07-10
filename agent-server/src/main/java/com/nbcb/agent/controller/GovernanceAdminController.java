package com.nbcb.agent.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nbcb.agent.governance.config.AgentGovernanceManager;
import com.nbcb.agent.governance.entity.AgentGovernanceEntity;
import com.nbcb.agent.governance.mapper.AgentGovernanceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ Agent 治理配置管理 API（开发/Mock 环境使用）
 * <p>
 * 提供用户黑名单、机构黑名单的增删查功能，配合前端页面验证治理组件有效性。
 * <p>
 * <b>安全约束</b>：
 * <ul>
 *   <li>通过 {@code agent-governance.admin.enabled} 控制开关，<b>生产环境必须设为 false</b></li>
 *   <li>Mock 开发环境显式开启：{@code agent-governance.admin.enabled=true}</li>
 *   <li>即使启用，路径 {@code /api/admin/**} 已被治理拦截器排除，不会产生死循环</li>
 *   <li>如需生产环境暴露管理 API，应接入 Spring Security + ADMIN 角色校验</li>
 * </ul>
 *
 * @author com.nbcb
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent-governance")
@ConditionalOnProperty(prefix = "agent-governance.admin", name = "enabled", havingValue = "true")
public class GovernanceAdminController {

    private final AgentGovernanceMapper mapper;
    private final AgentGovernanceManager governanceManager;

    public GovernanceAdminController(AgentGovernanceMapper mapper,
                                     AgentGovernanceManager governanceManager) {
        this.mapper = mapper;
        this.governanceManager = governanceManager;
    }

    // ==================== 配置总览 ====================

    /**
     * ★ 获取所有治理配置
     */
    @GetMapping("/all")
    public Map<String, Object> listAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routes", mapper.findAllRoutes());
        result.put("agents", mapper.selectList(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_AGENT)));
        result.put("users", mapper.selectList(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_USER)
                .orderByDesc(AgentGovernanceEntity::getId)));
        result.put("orgs", mapper.selectList(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_ORG)
                .orderByDesc(AgentGovernanceEntity::getId)));
        return result;
    }

    // ==================== 用户黑/白名单管理 ====================

    /**
     * ★ 添加或更新用户级配置（黑名单/白名单）
     */
    @PostMapping("/users")
    public Map<String, Object> upsertUser(@RequestBody Map<String, String> body) {
        String agentName = body.getOrDefault("agentName", "skill-agent");
        String userId = body.get("userId");
        String status = body.getOrDefault("status", "DISABLED");
        Integer effectiveMinutes = body.containsKey("effectiveMinutes")
                ? Integer.parseInt(body.get("effectiveMinutes")) : null;

        if (userId == null || userId.isBlank()) {
            return Map.of("success", false, "message", "userId 不能为空");
        }

        // 查询是否已存在，存在则更新，否则插入
        AgentGovernanceEntity existing = mapper.findUserWhitelist(agentName, userId).orElse(null);
        AgentGovernanceEntity entity = existing != null ? existing : new AgentGovernanceEntity();
        entity.setConfigType(AgentGovernanceEntity.TYPE_USER);
        entity.setAgentName(agentName);
        entity.setUserId(userId);
        entity.setStatus(status);
        entity.setEffectiveFrom(LocalDateTime.now());
        if (effectiveMinutes != null && effectiveMinutes > 0) {
            entity.setEffectiveTo(LocalDateTime.now().plusMinutes(effectiveMinutes));
        } else {
            entity.setEffectiveTo(null);
        }
        entity.setDescription(body.getOrDefault("description", "管理页面手动添加"));

        if (existing != null) {
            mapper.updateById(entity);
        } else {
            mapper.insert(entity);
        }
        governanceManager.refreshUser(agentName, userId);
        log.info("★ 用户配置已更新: agent={}, userId={}, status={}", agentName, userId, status);
        return Map.of("success", true, "message", "用户配置已更新", "id", entity.getId());
    }

    /**
     * ★ 删除用户级配置
     */
    @DeleteMapping("/users")
    public Map<String, Object> deleteUser(@RequestBody Map<String, String> body) {
        String agentName = body.getOrDefault("agentName", "skill-agent");
        String userId = body.get("userId");

        if (userId == null || userId.isBlank()) {
            return Map.of("success", false, "message", "userId 不能为空");
        }

        mapper.delete(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_USER)
                .eq(AgentGovernanceEntity::getAgentName, agentName)
                .eq(AgentGovernanceEntity::getUserId, userId));
        governanceManager.refreshUser(agentName, userId);
        log.info("★ 用户配置已删除: agent={}, userId={}", agentName, userId);
        return Map.of("success", true, "message", "用户配置已删除");
    }

    // ==================== 机构黑/白名单管理 ====================

    /**
     * ★ 添加或更新机构级配置（黑名单/白名单）
     */
    @PostMapping("/orgs")
    public Map<String, Object> upsertOrg(@RequestBody Map<String, String> body) {
        String agentName = body.getOrDefault("agentName", "skill-agent");
        String orgId = body.get("orgId");
        String status = body.getOrDefault("status", "DISABLED");
        Integer effectiveMinutes = body.containsKey("effectiveMinutes")
                ? Integer.parseInt(body.get("effectiveMinutes")) : null;

        if (orgId == null || orgId.isBlank()) {
            return Map.of("success", false, "message", "orgId 不能为空");
        }

        AgentGovernanceEntity existing = mapper.findOrgWhitelist(agentName, orgId).orElse(null);
        AgentGovernanceEntity entity = existing != null ? existing : new AgentGovernanceEntity();
        entity.setConfigType(AgentGovernanceEntity.TYPE_ORG);
        entity.setAgentName(agentName);
        entity.setOrgId(orgId);
        entity.setStatus(status);
        entity.setEffectiveFrom(LocalDateTime.now());
        if (effectiveMinutes != null && effectiveMinutes > 0) {
            entity.setEffectiveTo(LocalDateTime.now().plusMinutes(effectiveMinutes));
        } else {
            entity.setEffectiveTo(null);
        }
        entity.setDescription(body.getOrDefault("description", "管理页面手动添加"));

        if (existing != null) {
            mapper.updateById(entity);
        } else {
            mapper.insert(entity);
        }
        governanceManager.refreshOrg(agentName, orgId);
        log.info("★ 机构配置已更新: agent={}, orgId={}, status={}", agentName, orgId, status);
        return Map.of("success", true, "message", "机构配置已更新", "id", entity.getId());
    }

    /**
     * ★ 删除机构级配置
     */
    @DeleteMapping("/orgs")
    public Map<String, Object> deleteOrg(@RequestBody Map<String, String> body) {
        String agentName = body.getOrDefault("agentName", "skill-agent");
        String orgId = body.get("orgId");

        if (orgId == null || orgId.isBlank()) {
            return Map.of("success", false, "message", "orgId 不能为空");
        }

        mapper.delete(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_ORG)
                .eq(AgentGovernanceEntity::getAgentName, agentName)
                .eq(AgentGovernanceEntity::getOrgId, orgId));
        governanceManager.refreshOrg(agentName, orgId);
        log.info("★ 机构配置已删除: agent={}, orgId={}", agentName, orgId);
        return Map.of("success", true, "message", "机构配置已删除");
    }

    // ==================== 缓存刷新 ====================

    /**
     * ★ 强制刷新全部缓存
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        governanceManager.refreshAll();
        return Map.of("success", true, "message", "缓存已刷新");
    }

    // ==================== Agent 全局开关管理 ====================

    /**
     * ★ 切换 Agent 全局开关（ENABLED ⇄ DISABLED）
     */
    @PostMapping("/agents/toggle")
    public Map<String, Object> toggleAgent(@RequestBody Map<String, String> body) {
        String agentName = body.getOrDefault("agentName", "skill-agent");
        String newStatus = body.getOrDefault("status", "DISABLED");

        AgentGovernanceEntity existing = mapper.findAgentConfig(agentName).orElse(null);
        if (existing == null) {
            // 不存在则创建
            existing = new AgentGovernanceEntity();
            existing.setConfigType(AgentGovernanceEntity.TYPE_AGENT);
            existing.setAgentName(agentName);
        }
        existing.setStatus(newStatus);
        existing.setDefaultStatus(newStatus);
        if (existing.getId() != null) {
            mapper.updateById(existing);
        } else {
            mapper.insert(existing);
        }
        governanceManager.refreshAgent(agentName);
        log.info("★ Agent 全局开关已切换: agent={}, status={}", agentName, newStatus);
        return Map.of("success", true, "agentName", agentName, "status", newStatus,
                "message", "Agent [" + agentName + "] 已" + ("ENABLED".equals(newStatus) ? "启用" : "禁用"));
    }

    // ==================== 验证：检查指定用户/机构是否可以访问 ====================

    /**
     * ★ 查询指定用户+机构是否可访问指定 Agent
     */
    @GetMapping("/check")
    public Map<String, Object> check(
            @RequestParam(defaultValue = "skill-agent") String agentName,
            @RequestParam String userId,
            @RequestParam String orgId) {
        boolean enabled = governanceManager.isEnabled(agentName, userId, orgId);
        return Map.of(
                "agentName", agentName,
                "userId", userId,
                "orgId", orgId,
                "enabled", enabled,
                "message", enabled ? "允许访问" : "已被拦截"
        );
    }
}
