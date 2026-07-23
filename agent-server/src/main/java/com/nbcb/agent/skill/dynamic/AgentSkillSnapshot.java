package com.nbcb.agent.skill.dynamic;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 单次 Agent 执行使用的不可变 Skill 快照。
 */
public record AgentSkillSnapshot(String agentName, Map<String, VersionedSkill> skills) {

    public AgentSkillSnapshot {
        skills = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
    }

    public Optional<VersionedSkill> get(String skillName) {
        return Optional.ofNullable(skills.get(skillName));
    }
}
