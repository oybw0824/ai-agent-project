package com.nbcb.nacosmcpagent.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 从公共 Agent 表中装配出的只读运行定义。
 */
public record AgentDefinition(
        String agentId,
        String agentName,
        List<AgentNodeDefinition> nodes) {

    public AgentDefinition {
        nodes = List.copyOf(nodes);
    }

    /**
     * Agent 下的一个执行节点。节点有 Skill 或直连工具时构建 ReactAgent；
     * 没有任何工具绑定时仅构建 ChatModel。
     */
    public record AgentNodeDefinition(
            String nodeId,
            String systemPrompt,
            String userPrompt,
            BigDecimal temperature,
            Integer maxToken,
            ModelDefinition model,
            List<SkillDefinition> skills,
            List<ToolDefinition> agentTools) {

        public AgentNodeDefinition {
            skills = List.copyOf(skills);
            agentTools = List.copyOf(agentTools);
        }

        public boolean requiresAgent() {
            return !skills.isEmpty() || !agentTools.isEmpty();
        }
    }

    /**
     * 模型连接信息。CREDENTIAL_REF 当前直接存储 API Key，不在日志或接口中输出。
     */
    public record ModelDefinition(
            String modelId,
            String providerCode,
            String modelName,
            String endpointUri,
            String credentialRef) {
    }

    /**
     * 节点绑定的 Skill 及该 Skill 允许使用的工具。
     */
    public record SkillDefinition(
            String skillCode,
            List<ToolDefinition> tools) {

        public SkillDefinition {
            tools = List.copyOf(tools);
        }
    }

    /**
     * MCP 工具元数据。mcpCode 与 toolCode 共同定位启动时发现的工具。
     */
    public record ToolDefinition(
            String mcpCode,
            String toolCode) {
    }
}
