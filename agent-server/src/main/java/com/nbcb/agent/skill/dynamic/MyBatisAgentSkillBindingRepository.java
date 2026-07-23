package com.nbcb.agent.skill.dynamic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nbcb.agent.skill.dynamic.mapper.AgentSkillBindingMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 MyBatis-Plus 的绑定仓储实现。
 */
@Repository
public class MyBatisAgentSkillBindingRepository implements AgentSkillBindingRepository {

    private final AgentSkillBindingMapper mapper;

    public MyBatisAgentSkillBindingRepository(AgentSkillBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AgentSkillBinding> findEnabledByAgentName(String agentName) {
        return mapper.selectList(new LambdaQueryWrapper<AgentSkillBindingEntity>()
                        .eq(AgentSkillBindingEntity::getAgentName, agentName)
                        .eq(AgentSkillBindingEntity::getEnabled, true)
                        .orderByAsc(AgentSkillBindingEntity::getSkillName))
                .stream()
                .map(AgentSkillBindingEntity::toBinding)
                .toList();
    }

    @Override
    public Optional<AgentSkillBinding> find(String agentName, String skillName) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<AgentSkillBindingEntity>()
                        .eq(AgentSkillBindingEntity::getAgentName, agentName)
                        .eq(AgentSkillBindingEntity::getSkillName, skillName)))
                .map(AgentSkillBindingEntity::toBinding);
    }
}
