package com.nbcb.agent.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.AbstractSkillRegistry;
import lombok.extern.slf4j.Slf4j;
import com.nbcb.agent.domain.StreamEvent;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ★ Nacos AI Registry 技能注册表 — 继承框架 {@link AbstractSkillRegistry}
 * <p>
 * 桥接 Nacos 3.2 AI Registry（{@link NacosSkillLoader}）
 * 到 Spring AI Alibaba Agent Framework 的 SkillRegistry 抽象。
 * <p>
 * 关键职责：
 * <ul>
 *   <li>启动时从 NacosSkillLoader 加载所有技能 → 转换为框架的 {@link SkillMetadata}</li>
 *   <li>提供 {@link #readSkillContent(String)} → ReadSkillTool 调用，返回完整 SKILL.md</li>
 *   <li>生成技能列表提示词（{@link SystemPromptTemplate}）→ SkillsAgentHook 注入 system prompt</li>
 *   <li>继承 {@link #reload()} 支持定时刷新</li>
 * </ul>
 * <p>
 * 与 SkillsAgentHook 的协作流程：
 * <ol>
 *   <li>SkillsAgentHook.beforeAgent() 调用 {@link #listAll()} → 获取可用技能列表</li>
 *   <li>SkillsAgentHook 将技能元数据注入 system prompt（"可用技能: xxx, yyy"）</li>
 *   <li>LLM 决定调用 read_skill("skill-name") → ReadSkillTool 调用 {@link #readSkillContent(String)}</li>
 *   <li>返回完整 SKILL.md 内容 → LLM 按指令执行</li>
 * </ol>
 *
 * @author com.nbcb
 */
@Slf4j
public class NacosSkillRegistry extends AbstractSkillRegistry {

    /** Nacos 技能加载器（官方 AiService SDK → ZIP → SKILL.md） */
    private final NacosSkillLoader skillLoader;

    /** 系统提示词模板（注入到 Agent system prompt 的技能列表部分） */
    private final SystemPromptTemplate systemPromptTemplate;

    /** ★ 线程级技能调用追踪（ThreadLocal，支持并发请求隔离） */
    private static final ThreadLocal<LinkedHashSet<String>> CALLED_SKILLS = new ThreadLocal<>();

    /** ★ 线程级 SSE 发射器（流式推送用，非流式时为 null） */
    private static final ThreadLocal<SseEmitter> CURRENT_EMITTER = new ThreadLocal<>();

    /**
     * 构造函数 — 立即从 NacosSkillLoader 加载所有技能到注册表
     *
     * @param skillLoader Nacos 技能加载器
     */
    public NacosSkillRegistry(NacosSkillLoader skillLoader) {
        this.skillLoader = skillLoader;

        // ★ 构建系统提示词模板
        this.systemPromptTemplate = new SystemPromptTemplate(
                """
                        ## 可用技能列表
                        {skills_list}
                        
                        ## 使用说明
                        {skills_load_instructions}""");

        // ★ 构造时预加载所有已知技能
        loadSkillsToRegistry();
        log.info("★ NacosSkillRegistry 初始化完成：注册 {} 个技能", skills.size());
    }

    // ==================== AbstractSkillRegistry 抽象方法实现 ====================

    /**
     * ♻ 重新加载所有技能
     * <p>
     * 清空现有注册表 → 重新从 NacosSkillLoader 拉取 → 注册。
     * 覆盖父类实现以确保先清空再加载。
     */
    @Override
    public void reload() {
        log.info("♻ 重新加载 Nacos 技能注册表...");
        skills.clear();
        loadSkillsToRegistry();
        log.info("♻ 重新加载完成：{} 个技能", skills.size());
    }

    /**
     * ★ 从 NacosSkillLoader 加载所有技能到注册表
     * <p>
     * 遍历 {@link NacosSkillLoader#getLoadedSkills()} 返回的技能名称，
     * 加载每个技能的完整信息 → 转换为框架 {@link SkillMetadata} → 存入 {@code skills} map。
     * <p>
     * 调用时机：
     * <ul>
     *   <li>构造函数（首次初始化）</li>
     *   <li>{@link #reload()} 被调用时（由父类实现，先清空再调用此方法）</li>
     * </ul>
     */
    @Override
    protected void loadSkillsToRegistry() {
        List<String> skillNames = skillLoader.getLoadedSkills();

        if (skillNames.isEmpty()) {
            log.warn("NacosSkillLoader 中没有已加载的技能，注册表为空");
            return;
        }

        for (String skillName : skillNames) {
            try {
                NacosSkillMeta nacosMeta = skillLoader.getSkill(skillName);
                if (nacosMeta == null) {
                    log.warn("跳过技能 [{}]：NacosSkillLoader 返回 null", skillName);
                    continue;
                }

                // ★ 转换：Nacos Ai Client SkillMetadata → 框架 SkillMetadata
                String fullContent = nacosMeta.getRawContent();
                // rawContent 可能为 null（旧的 FALLBACK_SKILL 没有 rawContent）
                if (fullContent == null || fullContent.isEmpty()) {
                    fullContent = reconstructSkillMd(nacosMeta);
                }

                SkillMetadata frameworkMeta = SkillMetadata.builder()
                        .name(skillName)
                        .description(nacosMeta.getDescription())
                        .skillPath("/nacos/skills/" + skillName)
                        .source("nacos")
                        .fullContent(fullContent)
                        .build();

                skills.put(skillName, frameworkMeta);
                log.info("★ 注册 Nacos 技能: name={}, description={}, contentLength={}",
                        skillName, nacosMeta.getDescription(), fullContent.length());

            } catch (Exception e) {
                log.error("注册技能 [{}] 失败: {}", skillName, e.getMessage(), e);
            }
        }
    }

    // ==================== SkillRegistry 接口 — 自定义实现 ====================

    /**
     * ★ 读取技能完整内容 — ReadSkillTool 调用此方法
     * <p>
     * 返回完整的 SKILL.md 原始内容（YAML frontmatter + Markdown body），
     * 供 LLM 通过 read_skill 工具获取完整技能说明。
     * <p>
     * ★ 每次 LLM 调用 read_skill 时都会经过此方法，这是记录 LLM 实际使用了哪个技能的
     * 最佳拦截点。
     *
     * @param skillName 技能名称
     * @return 完整 SKILL.md 内容
     * @throws IOException 若技能不存在
     */
    @Override
    public String readSkillContent(String skillName) throws IOException {
        // ★ 记录 LLM 实际调用的技能
        log.info("★ LLM 调用 read_skill → 加载技能: [{}]", skillName);

        LinkedHashSet<String> called = CALLED_SKILLS.get();
        if (called != null) {
            called.add(skillName);
        }

        SkillMetadata meta = skills.get(skillName);
        if (meta == null) {
            log.warn("LLM 尝试加载不存在的技能: [{}]，可用技能: {}", skillName, skills.keySet());
            throw new IOException("技能 [" + skillName + "] 未在 Nacos 注册表中找到。"
                    + "可用技能: " + skills.keySet());
        }
        log.info("★ 技能 [{}] 加载成功，内容长度: {} 字符", skillName,
                meta.getFullContent() != null ? meta.getFullContent().length() : 0);
        // ★ 推送 skill_load 事件
        pushSseEvent(StreamEvent.skillLoad(skillName,
                meta.getFullContent() != null ? meta.getFullContent().length() : 0));
        return meta.getFullContent();
    }

    @Override
    public String getSkillLoadInstructions() {
        return """
                1. 首先分析用户问题的核心意图、领域和约束条件
                2. 根据上述「可用技能列表」中每个技能的描述，选择匹配度最高的技能
                3. 调用 read_skill("技能名称") 获取该技能的完整执行指令（SKILL.md）
                4. 严格按照技能指令中的执行流程和输出规范完成任务
                5. 直接输出最终结果，禁止在回答中提及技能名称或内部调度逻辑
                """;
    }

    @Override
    public String getRegistryType() {
        return "Nacos";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return systemPromptTemplate;
    }

    // ==================== 公共辅助方法 ====================

    /**
     * 获取已注册的技能名称列表（用于日志/调试）
     *
     * @return 按字母排序的技能名称列表
     */
    public List<String> getRegisteredSkillNames() {
        return skills.keySet().stream().sorted().collect(Collectors.toList());
    }

    // ==================== ★ ThreadLocal 生命周期管理 ====================

    /**
     * 开始记录当前请求的 LLM 技能调用（请求开始时调用）
     * <p>
     * 调用方需在 finally 中调用 {@link #endRecording()} 清理。
     */
    public static void beginRecording() {
        CALLED_SKILLS.set(new LinkedHashSet<>());
    }

    /**
     * 结束记录并返回 LLM 实际调用的技能列表（请求结束时调用）
     * <p>
     * 清理 ThreadLocal 后返回不可变列表，防止内存泄漏。
     *
     * @return LLM 实际调用的技能名称列表（按调用顺序）
     */
    public static List<String> endRecording() {
        LinkedHashSet<String> called = CALLED_SKILLS.get();
        CALLED_SKILLS.remove();
        CURRENT_EMITTER.remove();  // ★ 清理 SSE 发射器，防止内存泄漏
        return called != null ? List.copyOf(called) : List.of();
    }

    // ==================== ★ SSE 流式推送 ====================

    /**
     * 设置当前线程的 SSE 发射器（流式调用时设置）
     */
    public static void setSseEmitter(SseEmitter emitter) {
        CURRENT_EMITTER.set(emitter);
    }

    /**
     * 向当前线程的 SSE 发射器推送事件
     */
    private static void pushSseEvent(StreamEvent event) {
        SseEmitter emitter = CURRENT_EMITTER.get();
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType().name().toLowerCase())
                        .data(event.toJson()));
            } catch (IOException e) {
                log.debug("SSE 推送失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 重建 SKILL.md 格式内容（当 rawContent 为 null 时的回退方案）
     * <p>
     * 将 YAML frontmatter 属性 + Markdown body 重新组装为 SKILL.md 格式。
     */
    private String reconstructSkillMd(NacosSkillMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(meta.getName()).append("\n");
        sb.append("description: ").append(meta.getDescription()).append("\n");
        sb.append("version: ").append(meta.getVersion()).append("\n");
        if (meta.getTools() != null && !meta.getTools().isEmpty()) {
            sb.append("tools:\n");
            for (String tool : meta.getTools()) {
                sb.append("  - ").append(tool).append("\n");
            }
        }
        sb.append("---\n\n");
        sb.append(meta.getInstructions());
        return sb.toString();
    }
}
