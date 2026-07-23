package com.nbcb.nacosmcpagent.repository;

import com.nbcb.nacosmcpagent.domain.AgentDefinition;

import java.util.List;

/**
 * 已发布 Agent 运行定义查询接口。
 */
public interface AgentDefinitionRepository {

    List<AgentDefinition> findPublishedAgents();
}
