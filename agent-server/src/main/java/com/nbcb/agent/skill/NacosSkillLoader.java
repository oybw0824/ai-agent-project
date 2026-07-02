package com.nbcb.agent.skill;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.listener.AbstractNacosSkillListener;
import com.alibaba.nacos.api.ai.listener.NacosSkillEvent;
import com.nbcb.agent.metric.AgentMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Nacos 技能加载器 — 使用官方 AiService SDK
 * <p>
 * ★ 用 {@link AiService#subscribeSkill} + {@link AbstractNacosSkillListener}
 * 替代原来的 REST API 轮询。Nacos 推送变更时自动刷新。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class NacosSkillLoader {

    /** SKILL.md 中 YAML frontmatter 正则：--- ... --- */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    /** 默认加载的技能名称 */
    private static final String DEFAULT_SKILL_NAME = "general-assistant";

    /** 启动时预加载的技能列表（逗号分隔） */
    @Value("${agent.skill.preload:general-assistant,code-generator,data-analyst,content-creator,document-parser}")
    private String preloadSkills;

    /** 默认 Nacos 地址 */
    private static final String DEFAULT_NACOS_ADDR = "127.0.0.1:8848";

    @Value("${NACOS_ADDR:" + DEFAULT_NACOS_ADDR + "}")
    private String nacosAddr;

    @Value("${NACOS_NAMESPACE:public}")
    private String namespace;

    /** Nacos AI 服务客户端（官方 SDK） */
    private AiService aiService;

    /** Skill 缓存 */
    private final ConcurrentHashMap<String, NacosSkillMeta> skillCache = new ConcurrentHashMap<>();

    private volatile boolean nacosAvailable = false;

    /** 内置默认 Skill（Nacos 不可用时的回退） */
    private static final NacosSkillMeta FALLBACK_SKILL = createFallbackSkill();

    @PostConstruct
    public void init() {
        try {
            Properties props = new Properties();
            props.setProperty(PropertyKeyConst.SERVER_ADDR, nacosAddr);
            props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
            props.setProperty(AiConstants.AI_TRANSPORT_MODE, AiConstants.AI_TRANSPORT_MODE_HTTP);
            aiService = AiFactory.createAiService(props);
            nacosAvailable = true;
            log.info("Nacos AiService SDK 初始化成功, server={}, namespace={}", nacosAddr, namespace);

            int loaded = 0;
            for (String skillName : preloadSkills.split(",")) {
                String trimmed = skillName.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        loadSkill(trimmed);
                        loaded++;
                    } catch (Exception e) {
                        log.warn("预加载 Skill [{}] 失败: {}", trimmed, e.getMessage());
                    }
                }
            }
            log.info("★ 技能预加载完成：{}/{} 个", loaded, preloadSkills.split(",").length);
        } catch (Exception e) {
            log.warn("Nacos AiService SDK 初始化失败: {}，使用内置默认 Skill", e.getMessage());
            nacosAvailable = false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (aiService != null) {
            try {
                aiService.shutdown();
            } catch (Exception e) {
                log.debug("Nacos AiService shutdown error", e);
            }
        }
    }

    // ==================== Skill 操作 ====================

    public NacosSkillMeta getSkill(String skillName) {
        NacosSkillMeta cached = skillCache.get(skillName);
        if (cached != null) return cached;
        return nacosAvailable ? loadSkill(skillName) : FALLBACK_SKILL;
    }

    // ==================== 状态查询 ====================

    public List<String> getLoadedSkills() {
        return skillCache.keySet().stream().sorted().collect(Collectors.toList());
    }

    public int getLoadedSkillCount() { return skillCache.size(); }
    public boolean isNacosAvailable() { return nacosAvailable; }

    /**
     * ★ 暴露 AiService 供 PromptService 等组件复用
     * <p>
     * 避免重复创建 Nacos 连接，统一管理生命周期（由本类 {@link #destroy()} 关闭）。
     *
     * @return Nacos AiService 实例（Nacos 不可用时为 null）
     */
    public AiService getAiService() {
        return aiService;
    }

    // ==================== ★ 官方 SDK 订阅 ====================

    private NacosSkillMeta loadSkill(String skillName) {
        try {
            byte[] zip = aiService.subscribeSkill(skillName, null, null,
                    new AbstractNacosSkillListener() {
                        @Override
                        public void onEvent(NacosSkillEvent event) {
                            log.info("★ Nacos 推送 Skill 变更: name={}, version={}",
                                    event.getSkillName(), event.getResolvedVersion());
                            skillCache.remove(skillName);
                        }
                    });

            NacosSkillMeta meta = parseSkillZip(skillName, zip);
            if (meta != null) {
                skillCache.put(skillName, meta);
                log.info("Skill[{}] 已加载：name={}, tools={}, instructions长度={}",
                        skillName, meta.getName(), meta.getTools(),
                        meta.getInstructions() != null ? meta.getInstructions().length() : 0);
                return meta;
            }
        } catch (Exception e) {
            log.warn("加载 Skill[{}] 失败: {}", skillName, e.getMessage());
        }
        skillCache.put(skillName, FALLBACK_SKILL);
        return FALLBACK_SKILL;
    }

    // ==================== ZIP 解析 ====================

    private NacosSkillMeta parseSkillZip(String skillName, byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) return null;

        String content = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equalsIgnoreCase("SKILL.md")
                        || name.endsWith("/SKILL.md")
                        || name.endsWith("\\SKILL.md")) {
                    content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (IOException e) {
            log.error("读取 Skill[{}] ZIP 失败", skillName, e);
            return null;
        }

        if (content == null) {
            log.warn("Skill[{}] ZIP 中未找到 SKILL.md", skillName);
            return null;
        }
        return parseFrontmatter(content);
    }

    @SuppressWarnings("unchecked")
    private NacosSkillMeta parseFrontmatter(String content) {
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) {
            log.warn("SKILL.md 未找到 YAML frontmatter (---...---)");
            return null;
        }

        String yamlStr = m.group(1);
        String markdownBody = m.group(2).trim();

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> frontmatter = yaml.load(yamlStr);

            NacosSkillMeta meta = new NacosSkillMeta();
            meta.setName(getStr(frontmatter, "name", "unknown"));
            meta.setDescription(getStr(frontmatter, "description", ""));
            meta.setVersion(getStr(frontmatter, "version", "1.0.0"));

            Object tools = frontmatter.get("tools");
            if (tools instanceof List) {
                meta.setTools(((List<Object>) tools).stream().map(Object::toString).toList());
            }

            meta.setInstructions(markdownBody);
            meta.setRawContent(content);
            return meta;
        } catch (Exception e) {
            log.error("解析 SKILL.md YAML frontmatter 失败", e);
            return null;
        }
    }

    // ==================== 回退 Skill ====================

    private static NacosSkillMeta createFallbackSkill() {
        NacosSkillMeta skill = new NacosSkillMeta();
        skill.setName("通用助手（默认）");
        skill.setDescription("内置默认 Skill，Nacos 不可用时使用");
        skill.setVersion("1.0.0");
        skill.setTools(List.of("getWeatherByCity", "calculate"));
        skill.setInstructions("""
                # 通用助手

                ## 触发场景
                当用户询问天气或需要数值计算时触发。

                ## 执行流程
                1. 识别用户意图（天气查询 or 数值计算）
                2. 调用对应的 MCP 工具
                3. 格式化工具返回结果
                4. 给出生活建议（如天气相关）

                ## 规则
                - 始终用中文回复
                - 计算结果需附上原始表达式
                - 天气查询后给出生活建议
                """);
        return skill;
    }

    private String getStr(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }
}