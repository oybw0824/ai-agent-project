package com.nbcb.agent.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.domain.AgentChatResponse;
import com.nbcb.agent.domain.ChatRequest;
import com.nbcb.agent.service.AgentService;
import com.nbcb.agent.service.AgentStreamService;
import com.nbcb.agent.service.McpCatalogService;
import com.nbcb.agent.skill.dynamic.DynamicSkillProperties;
import com.nbcb.agent.skill.dynamic.DynamicSkillRuntimeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话接口 — REST API
 *
 * @author com.nbcb
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final AgentService agentService;
    private final AgentStreamService streamService;
    private final DynamicSkillRuntimeService dynamicSkillRuntimeService;
    private final DynamicSkillProperties dynamicSkillProperties;
    private final McpCatalogService mcpCatalogService;

    public ChatController(AgentService agentService,
                          AgentStreamService streamService,
                          DynamicSkillRuntimeService dynamicSkillRuntimeService,
                          DynamicSkillProperties dynamicSkillProperties,
                          McpCatalogService mcpCatalogService) {
        this.agentService = agentService;
        this.streamService = streamService;
        this.dynamicSkillRuntimeService = dynamicSkillRuntimeService;
        this.dynamicSkillProperties = dynamicSkillProperties;
        this.mcpCatalogService = mcpCatalogService;
    }

    /**
     * 查询当前加载的所有 Skill 信息
     */
    @GetMapping("/skills")
    public Map<String, Object> listSkills() {
        List<Map<String, Object>> skills = dynamicSkillRuntimeService.listSkillStatuses(
                dynamicSkillProperties.getAgentName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentName", dynamicSkillProperties.getAgentName());
        result.put("source", "database+nas");
        result.put("skillCount", skills.size());
        result.put("skills", skills);
        return result;
    }

    /**
     * ★ 查询当前注册的 MCP 工具列表
     * <p>
     * 工具来源：通过 Streamable HTTP 连接 Higress AI 网关。
     * 返回所有已注册的 MCP 工具的元数据（名称、描述、输入参数 schema）。
     *
     * @return 工具元数据列表（含 Nacos 来源标记）
     */
    @GetMapping("/tools")
    public List<Map<String, Object>> listTools() {
        return mcpCatalogService.listToolMetadata();
    }

    /**
     * 处理 Agent 对话请求
     * <p>
     * 技能已直接注入 system prompt，LLM 无需额外调用即可获取完整指令。
     *
     * @param request 包含 question（必填）的请求体
     * @return ChatResponse 对话响应
     */
    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String question = request.getQuestion();

        log.info("收到对话请求，questionLen={}", question.length());
        return agentService.chat(question);
    }

    /**
     * ★ 流式 Agent 对话（SSE）— 实时展示完整思考过程
     * <p>
     * ★ v2.1：LLM 自主根据用户问题匹配技能，无需传入 skillId。
     * <p>
     * 返回 {@code text/event-stream} 流，事件类型：
     * <table>
     *   <tr><td>{@code thinking}</td><td>Agent 开始分析</td></tr>
     *   <tr><td>{@code skill_load}</td><td>LLM 加载技能（read_skill）</td></tr>
     *   <tr><td>{@code tool_call}</td><td>LLM 调用 MCP 工具（含工具名+入参）</td></tr>
     *   <tr><td>{@code tool_result}</td><td>MCP 工具返回结果</td></tr>
     *   <tr><td>{@code message}</td><td>Agent 最终回答</td></tr>
     *   <tr><td>{@code done}</td><td>对话完成（含完整元数据）</td></tr>
     *   <tr><td>{@code error}</td><td>发生错误</td></tr>
     * </table>
     * <p>
     * 前端示例（fetch + ReadableStream 以支持 POST）：
     * <pre>
     * const resp = await fetch('/chat/stream', {
     *     method: 'POST', headers: {'Content-Type': 'application/json'},
     *     body: JSON.stringify({ question: '帮我写一个Python排序函数' })
     * });
     * const reader = resp.body.getReader();
     * </pre>
     *
     * @param request 包含 question（必填）的请求体
     * @return SSE 事件流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        String question = request.getQuestion();

        log.info("收到流式对话请求，questionLen={}", question.length());
        return streamService.streamChat(question);
    }
}
