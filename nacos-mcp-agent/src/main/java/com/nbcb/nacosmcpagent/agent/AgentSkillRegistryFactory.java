package com.nbcb.nacosmcpagent.agent;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry.DEFAULT_SYSTEM_PROMPT_TEMPLATE;

/**
 * 根据 AI_SKILL.SKILL_CODE 精确加载 classpath:skills/{skillCode}/SKILL.md。
 */
@Slf4j
public class AgentSkillRegistryFactory {

    private static final Pattern SKILL_CODE_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final ResourceLoader resourceLoader;
    private final String classpathRoot;
    private final SystemPromptTemplate systemPromptTemplate;

    public AgentSkillRegistryFactory(
            ResourceLoader resourceLoader,
            String classpathRoot) {
        this.resourceLoader = resourceLoader;
        this.classpathRoot = normalizeClasspathRoot(classpathRoot);
        this.systemPromptTemplate = SystemPromptTemplate.builder()
                .template(DEFAULT_SYSTEM_PROMPT_TEMPLATE)
                .build();
    }

    public SkillRegistry create(
            String agentId,
            Collection<String> skillCodes) {
        Map<String, SkillMetadata> skills = new LinkedHashMap<>();
        for (String skillCode : skillCodes) {
            Optional<SkillMetadata> optionalMetadata =
                    loadSkill(agentId, skillCode);
            if (optionalMetadata.isEmpty()) {
                continue;
            }
            SkillMetadata metadata = optionalMetadata.get();
            if (skills.putIfAbsent(
                    metadata.getName(),
                    metadata) != null) {
                throw new IllegalStateException(
                        "Agent 绑定 Skill 重复: agentId=" + agentId
                                + ", skillCode=" + skillCode);
            }
        }
        return new DatabaseBoundSkillRegistry(
                skills,
                classpathRoot,
                systemPromptTemplate);
    }

    private Optional<SkillMetadata> loadSkill(
            String agentId,
            String skillCode) {
        validateSkillCode(skillCode);
        String location = "classpath:" + classpathRoot
                + "/" + skillCode + "/SKILL.md";
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("已绑定 Skill 的资源目录不存在，跳过注册: agentId={}, skillCode={}, location={}",
                    agentId, skillCode, location);
            return Optional.empty();
        }
        try {
            String rawContent = resource.getContentAsString(
                    StandardCharsets.UTF_8);
            Map<String, String> frontmatter =
                    parseFrontmatter(rawContent);
            String description = requireFrontmatter(
                    resource, frontmatter, "description");
            String content = removeFrontmatter(rawContent);
            SkillMetadata metadata = SkillMetadata.builder()
                    .name(skillCode)
                    .description(description)
                    .skillPath("classpath:" + classpathRoot + "/" + skillCode)
                    .source("classpath")
                    .fullContent(content)
                    .build();
            return Optional.of(metadata);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(
                    "加载 Skill 失败: " + location, ex);
        }
    }

    private static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!content.startsWith("---")) {
            return values;
        }
        int endIndex = content.indexOf("---", 3);
        if (endIndex < 0) {
            return values;
        }
        String block = content.substring(3, endIndex);
        for (String line : block.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String removeFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return content;
        }
        int endIndex = content.indexOf("---", 3);
        if (endIndex < 0) {
            return content;
        }
        return content.substring(endIndex + 3).trim();
    }

    private static String requireFrontmatter(
            Resource resource,
            Map<String, String> frontmatter,
            String key) {
        String value = frontmatter.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Skill frontmatter 缺少 " + key + ": "
                            + resource.getDescription());
        }
        return value.trim();
    }

    private static void validateSkillCode(String skillCode) {
        if (skillCode == null
                || !SKILL_CODE_PATTERN.matcher(skillCode).matches()) {
            throw new IllegalStateException(
                    "AI_SKILL.SKILL_CODE 格式非法: " + skillCode);
        }
    }

    private static String normalizeClasspathRoot(String value) {
        if (value == null || value.isBlank()) {
            return "skills";
        }
        String root = value.trim();
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return root.isBlank() ? "skills" : root;
    }

}
