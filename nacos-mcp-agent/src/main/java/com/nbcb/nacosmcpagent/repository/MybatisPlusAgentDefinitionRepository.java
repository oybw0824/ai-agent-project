package com.nbcb.nacosmcpagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nbcb.nacosmcpagent.domain.AgentDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.AgentNodeDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ModelDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.SkillDefinition;
import com.nbcb.nacosmcpagent.domain.AgentDefinition.ToolDefinition;
import com.nbcb.nacosmcpagent.entity.AiAgentEntity;
import com.nbcb.nacosmcpagent.entity.AiAgentNodeEntity;
import com.nbcb.nacosmcpagent.entity.AiAgentNodeSkillBindingEntity;
import com.nbcb.nacosmcpagent.entity.AiModelEntity;
import com.nbcb.nacosmcpagent.entity.AiSkillEntity;
import com.nbcb.nacosmcpagent.entity.AiToolBindingEntity;
import com.nbcb.nacosmcpagent.entity.AiToolEntity;
import com.nbcb.nacosmcpagent.mapper.AiAgentMapper;
import com.nbcb.nacosmcpagent.mapper.AiAgentNodeMapper;
import com.nbcb.nacosmcpagent.mapper.AiAgentNodeSkillBindingMapper;
import com.nbcb.nacosmcpagent.mapper.AiModelMapper;
import com.nbcb.nacosmcpagent.mapper.AiSkillMapper;
import com.nbcb.nacosmcpagent.mapper.AiToolBindingMapper;
import com.nbcb.nacosmcpagent.mapper.AiToolMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisPlusAgentDefinitionRepository
        implements AgentDefinitionRepository {

    private static final String ACTIVE = "0";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String BINDING_TYPE_SKILL = "SKILL";
    private static final String BINDING_TYPE_AGENT_NODE = "AGENT_NODE";

    private final AiAgentMapper agentMapper;
    private final AiAgentNodeMapper agentNodeMapper;
    private final AiModelMapper modelMapper;
    private final AiSkillMapper skillMapper;
    private final AiAgentNodeSkillBindingMapper nodeSkillBindingMapper;
    private final AiToolMapper toolMapper;
    private final AiToolBindingMapper toolBindingMapper;

    public MybatisPlusAgentDefinitionRepository(
            AiAgentMapper agentMapper,
            AiAgentNodeMapper agentNodeMapper,
            AiModelMapper modelMapper,
            AiSkillMapper skillMapper,
            AiAgentNodeSkillBindingMapper nodeSkillBindingMapper,
            AiToolMapper toolMapper,
            AiToolBindingMapper toolBindingMapper) {
        this.agentMapper = agentMapper;
        this.agentNodeMapper = agentNodeMapper;
        this.modelMapper = modelMapper;
        this.skillMapper = skillMapper;
        this.nodeSkillBindingMapper = nodeSkillBindingMapper;
        this.toolMapper = toolMapper;
        this.toolBindingMapper = toolBindingMapper;
    }

    @Override
    public List<AgentDefinition> findPublishedAgents() {
        return agentMapper.selectList(new LambdaQueryWrapper<AiAgentEntity>()
                        .eq(AiAgentEntity::getStatus, PUBLISHED)
                        .eq(AiAgentEntity::getIsDelete, ACTIVE)
                        .orderByAsc(AiAgentEntity::getAgentId))
                .stream()
                .map(agent -> loadAgent(
                        agent.getAgentId(),
                        agent.getAgentName()))
                .toList();
    }

    private AgentDefinition loadAgent(
            String agentId,
            String agentName) {
        List<AiAgentNodeEntity> nodes = agentNodeMapper.selectList(
                new LambdaQueryWrapper<AiAgentNodeEntity>()
                        .eq(AiAgentNodeEntity::getAgentId, agentId)
                        .eq(AiAgentNodeEntity::getIsDelete, ACTIVE)
                        .orderByAsc(AiAgentNodeEntity::getNodeId));
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    "Agent 未配置有效节点: " + agentId);
        }
        return new AgentDefinition(
                agentId,
                agentName,
                nodes.stream()
                        .map(node -> loadNode(agentId, node))
                        .toList());
    }

    private AgentNodeDefinition loadNode(
            String agentId,
            AiAgentNodeEntity node) {
        return new AgentNodeDefinition(
                node.getNodeId(),
                node.getNodeSystemPrompt(),
                node.getNodeUserPrompt(),
                node.getTemperature(),
                node.getMaxToken(),
                loadModel(agentId, node.getModelId()),
                loadSkills(node.getNodeId()),
                loadTools(BINDING_TYPE_AGENT_NODE, node.getNodeId()));
    }

    private ModelDefinition loadModel(
            String agentId,
            String modelId) {
        List<AiModelEntity> models = modelMapper.selectList(
                new LambdaQueryWrapper<AiModelEntity>()
                        .eq(AiModelEntity::getModelId, modelId)
                        .eq(AiModelEntity::getStatus, PUBLISHED)
                        .eq(AiModelEntity::getIsDelete, ACTIVE));
        if (models.size() != 1) {
            throw new IllegalStateException(
                    "Agent " + agentId + " 引用的已发布模型不存在或不唯一: "
                            + modelId);
        }
        AiModelEntity model = models.get(0);
        return new ModelDefinition(
                model.getModelId(),
                model.getProviderCode(),
                model.getModelName(),
                model.getEndpointUri(),
                model.getCredentialRef());
    }

    private List<SkillDefinition> loadSkills(String nodeId) {
        List<String> skillIds = nodeSkillBindingMapper.selectList(
                        new LambdaQueryWrapper<AiAgentNodeSkillBindingEntity>()
                                .eq(AiAgentNodeSkillBindingEntity::getNodeId,
                                        nodeId)
                                .eq(AiAgentNodeSkillBindingEntity::getIsDelete,
                                        ACTIVE))
                .stream()
                .map(AiAgentNodeSkillBindingEntity::getSkillId)
                .toList();
        if (skillIds.isEmpty()) {
            return List.of();
        }
        return skillMapper.selectList(new LambdaQueryWrapper<AiSkillEntity>()
                        .in(AiSkillEntity::getSkillId, skillIds)
                        .eq(AiSkillEntity::getStatus, PUBLISHED)
                        .eq(AiSkillEntity::getIsDelete, ACTIVE)
                        .orderByAsc(AiSkillEntity::getSkillCode))
                .stream()
                .map(skill -> new SkillDefinition(
                        skill.getSkillCode(),
                        loadTools(BINDING_TYPE_SKILL, skill.getSkillId())))
                .toList();
    }

    private List<ToolDefinition> loadTools(
            String bindingType,
            String bindingId) {
        List<String> toolIds = toolBindingMapper.selectList(
                        new LambdaQueryWrapper<AiToolBindingEntity>()
                                .eq(AiToolBindingEntity::getBindingType,
                                        bindingType)
                                .eq(AiToolBindingEntity::getBindingId,
                                        bindingId)
                                .eq(AiToolBindingEntity::getIsDelete,
                                        ACTIVE))
                .stream()
                .map(AiToolBindingEntity::getToolId)
                .toList();
        if (toolIds.isEmpty()) {
            return List.of();
        }
        return toolMapper.selectList(new LambdaQueryWrapper<AiToolEntity>()
                        .in(AiToolEntity::getToolId, toolIds)
                        .eq(AiToolEntity::getIsDelete, ACTIVE)
                        .orderByAsc(AiToolEntity::getMcpCode)
                        .orderByAsc(AiToolEntity::getToolCode))
                .stream()
                .map(tool -> new ToolDefinition(
                        tool.getMcpCode(),
                        tool.getToolCode()))
                .toList();
    }
}
