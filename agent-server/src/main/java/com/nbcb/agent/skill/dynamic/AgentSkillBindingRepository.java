package com.nbcb.agent.skill.dynamic;

import java.util.List;
import java.util.Optional;

/**
 * Agent-Skill 绑定查询接口。
 */
public interface AgentSkillBindingRepository {

    List<AgentSkillBinding> findEnabledByAgentName(String agentName);

    Optional<AgentSkillBinding> find(String agentName, String skillName);
}
