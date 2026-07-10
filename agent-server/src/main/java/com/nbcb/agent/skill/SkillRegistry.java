package com.nbcb.agent.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.AbstractSkillRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能注册表 — 最简实现：继承 {@link AbstractSkillRegistry}，从 classpath:skills/*.md 加载
 * <p>
 * 配合框架 {@code SkillsAgentHook} 使用，LLM 通过 read_skill 工具按需获取技能完整内容。
 *
 * @author com.nbcb
 */
@Slf4j
@Component
public class SkillRegistry extends AbstractSkillRegistry {

    private static final String SKILLS_LOCATION = "classpath:skills/*.md";
    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private final SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate("""
            ## 可用技能
            {skills_list}

            {skills_load_instructions}""");

    @PostConstruct
    void scan() {
        loadSkillsToRegistry();
        log.info("★ SkillRegistry 初始化：{} 个技能", skills.size());
    }

    // ==================== AbstractSkillRegistry 抽象方法 ====================

    @Override
    protected void loadSkillsToRegistry() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SKILLS_LOCATION);
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null || !filename.endsWith(".md")) continue;
                String key = filename.substring(0, filename.length() - 3);
                try (InputStream is = r.getInputStream()) {
                    String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    SkillMetadata meta = parseAndBuild(key, raw);
                    if (meta != null) skills.put(key, meta);
                }
            }
            log.info("★ 扫描 classpath:skills/ 完成：{}/{} 个", skills.size(), resources.length);
        } catch (Exception e) {
            log.warn("扫描 classpath:skills/ 失败: {}", e.getMessage());
        }
        if (skills.isEmpty()) {
            log.warn("未加载到任何技能，使用内置兜底");
        }
    }

    @Override
    public String readSkillContent(String skillName) throws java.io.IOException {
        SkillMetadata meta = skills.get(skillName);
        if (meta == null) throw new java.io.IOException(
                "技能 [" + skillName + "] 未找到，可用: " + skills.keySet());
        return meta.getFullContent();
    }

    @Override
    public String getSkillLoadInstructions() {
        return "选择匹配的技能后，调用 read_skill(\"技能名\") 获取完整执行指令，严格按指令执行。";
    }

    @Override
    public String getRegistryType() { return "classpath"; }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() { return systemPromptTemplate; }

    // ==================== 内部解析 ====================

    private SkillMetadata parseAndBuild(String key, String raw) {
        Matcher m = FRONTMATTER.matcher(raw);
        if (!m.find()) { log.warn("[{}] 无 YAML frontmatter", key); return null; }
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> fm = yaml.load(m.group(1));
            String desc = fm.get("description") != null ? fm.get("description").toString() : "";
            return SkillMetadata.builder()
                    .name(key).description(desc)
                    .skillPath("/skills/" + key)
                    .source("classpath").fullContent(raw).build();
        } catch (Exception e) {
            log.error("[{}] YAML 解析失败", key, e);
            return null;
        }
    }
}
