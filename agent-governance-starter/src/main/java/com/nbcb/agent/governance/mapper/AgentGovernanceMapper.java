package com.nbcb.agent.governance.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nbcb.agent.governance.entity.AgentGovernanceEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * ★ 统一治理配置 Mapper — 通过 configType 区分四种配置类型
 *
 * @author com.nbcb
 */
@Mapper
public interface AgentGovernanceMapper extends BaseMapper<AgentGovernanceEntity> {

    /** 查询 Agent 全局配置 */
    default Optional<AgentGovernanceEntity> findAgentConfig(String agentName) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_AGENT)
                .eq(AgentGovernanceEntity::getAgentName, agentName)));
    }

    /** 查询用户白名单 */
    default Optional<AgentGovernanceEntity> findUserWhitelist(String agentName, String userId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_USER)
                .eq(AgentGovernanceEntity::getAgentName, agentName)
                .eq(AgentGovernanceEntity::getUserId, userId)));
    }

    /** 查询机构白名单 */
    default Optional<AgentGovernanceEntity> findOrgWhitelist(String agentName, String orgId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_ORG)
                .eq(AgentGovernanceEntity::getAgentName, agentName)
                .eq(AgentGovernanceEntity::getOrgId, orgId)));
    }

    default Optional<AgentGovernanceEntity> findGlobalChannelBlock(String channelCode) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_CHANNEL)
                .eq(AgentGovernanceEntity::getChannelCode, channelCode)));
    }

    default Optional<AgentGovernanceEntity> findGlobalUserBlock(String userId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_USER)
                .and(wrapper -> wrapper.isNull(AgentGovernanceEntity::getAgentName)
                        .or()
                        .eq(AgentGovernanceEntity::getAgentName, ""))
                .eq(AgentGovernanceEntity::getUserId, userId)));
    }

    default Optional<AgentGovernanceEntity> findGlobalOrgBlock(String orgId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_ORG)
                .and(wrapper -> wrapper.isNull(AgentGovernanceEntity::getAgentName)
                        .or()
                        .eq(AgentGovernanceEntity::getAgentName, ""))
                .eq(AgentGovernanceEntity::getOrgId, orgId)));
    }

    /** 查询所有路由配置 */
    default List<AgentGovernanceEntity> findAllRoutes() {
        return selectList(new LambdaQueryWrapper<AgentGovernanceEntity>()
                .eq(AgentGovernanceEntity::getConfigType, AgentGovernanceEntity.TYPE_ROUTE)
                .orderByAsc(AgentGovernanceEntity::getId));
    }
}
