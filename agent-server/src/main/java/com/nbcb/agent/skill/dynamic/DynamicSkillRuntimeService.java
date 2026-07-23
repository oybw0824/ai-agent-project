package com.nbcb.agent.skill.dynamic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态 Skill 版本协调服务。
 */
@Slf4j
@Service
public class DynamicSkillRuntimeService {

    private final DynamicSkillProperties properties;
    private final AgentSkillBindingRepository repository;
    private final SkillFileLoader fileLoader;
    private final AgentSkillLocalStore localStore;

    public DynamicSkillRuntimeService(DynamicSkillProperties properties,
                                      AgentSkillBindingRepository repository,
                                      SkillFileLoader fileLoader,
                                      AgentSkillLocalStore localStore) {
        this.properties = properties;
        this.repository = repository;
        this.fileLoader = fileLoader;
        this.localStore = localStore;
    }

    /**
     * 读取当前已就绪快照，不访问数据库或 NAS。
     */
    public AgentSkillSnapshot currentSnapshot(String agentName) {
        validateAgent(agentName);
        return localStore.requireReadySnapshot(agentName);
    }

    /**
     * 强制重新读取全部启用绑定，并在全部成功后原子替换 Agent 快照。
     */
    public AgentSkillSnapshot reloadAll(String agentName) {
        validateAgent(agentName);
        return localStore.withReloadLock(agentName, () -> {
            List<AgentSkillBinding> bindings = queryEnabledBindings(agentName);
            Map<String, VersionedSkill> candidate = new LinkedHashMap<>();
            for (AgentSkillBinding binding : bindings) {
                candidate.put(binding.skillName(), load(binding));
            }

            AgentSkillSnapshot replacement = new AgentSkillSnapshot(agentName, candidate);
            localStore.replaceSnapshot(agentName, replacement);
            log.info("Agent Skill 全量快照已替换: agent={}, skillCount={}",
                    agentName, replacement.skills().size());
            return replacement;
        });
    }

    /**
     * 强制重新读取当前数据库绑定的版本文件。
     */
    public VersionedSkill reload(String agentName, String skillName) {
        validateAgent(agentName);
        return localStore.withReloadLock(agentName, () -> {
            AgentSkillBinding binding = queryBinding(agentName, skillName);
            VersionedSkill loaded = load(binding);
            AgentSkillSnapshot current = localStore.getSnapshot(agentName);
            Map<String, VersionedSkill> updated = current == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(current.skills());
            updated.put(skillName, loaded);
            localStore.replaceSnapshot(agentName, new AgentSkillSnapshot(agentName, updated));
            log.info("Agent Skill 单项快照已替换: agent={}, skill={}, version={}",
                    agentName, skillName, loaded.version());
            return loaded;
        });
    }

    /**
     * 查询目标版本与本实例加载状态，不触发 NAS 读取。
     */
    public List<Map<String, Object>> listSkillStatuses(String agentName) {
        validateAgent(agentName);
        AgentSkillSnapshot current = localStore.getSnapshot(agentName);
        return queryEnabledBindings(agentName).stream().map(binding -> {
            VersionedSkill loaded = current == null
                    ? null : current.get(binding.skillName()).orElse(null);
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("name", binding.skillName());
            status.put("version", binding.skillVersion());
            status.put("targetVersion", binding.skillVersion());
            status.put("loadedVersion", loaded != null ? loaded.version() : null);
            status.put("status", loaded == null ? "NOT_LOADED"
                    : binding.skillVersion().equals(loaded.version()) ? "LOADED" : "OUTDATED");
            status.put("description", loaded != null ? loaded.definition().description() : "");
            return status;
        }).toList();
    }

    private VersionedSkill load(AgentSkillBinding binding) {
        validateBinding(binding);
        SkillDefinition definition = fileLoader.load(binding.skillName(),
                binding.skillVersion(), binding.skillFilePath());
        return new VersionedSkill(binding.skillVersion(), definition);
    }

    private AgentSkillBinding queryBinding(String agentName, String skillName) {
        try {
            return repository.find(agentName, skillName)
                    .filter(AgentSkillBinding::enabled)
                    .orElseThrow(() -> new DynamicSkillException(
                            DynamicSkillErrorCode.AGENT_SKILL_NOT_BOUND,
                            "Agent 未绑定该 Skill 或绑定未启用: " + skillName));
        } catch (DynamicSkillException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw bindingQueryFailed(ex);
        }
    }

    private List<AgentSkillBinding> queryEnabledBindings(String agentName) {
        try {
            return repository.findEnabledByAgentName(agentName);
        } catch (RuntimeException ex) {
            throw bindingQueryFailed(ex);
        }
    }

    private void validateAgent(String agentName) {
        if (!properties.getAgentName().equals(agentName)) {
            throw new DynamicSkillException(DynamicSkillErrorCode.AGENT_NOT_FOUND,
                    "Agent 不存在: " + agentName);
        }
    }

    private void validateBinding(AgentSkillBinding binding) {
        if (binding.skillName() == null || binding.skillName().isBlank()
                || binding.skillVersion() == null || binding.skillVersion().isBlank()
                || binding.skillFilePath() == null || binding.skillFilePath().isBlank()) {
            throw new DynamicSkillException(DynamicSkillErrorCode.SKILL_PARSE_FAILED,
                    "Agent-Skill 绑定缺少必要字段");
        }
    }

    private DynamicSkillException bindingQueryFailed(RuntimeException cause) {
        return new DynamicSkillException(DynamicSkillErrorCode.SKILL_BINDING_QUERY_FAILED,
                "查询 Agent-Skill 绑定失败", cause);
    }
}
